/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.monitor;

//Checkstyle: stop

import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.AbstractQueuedSynchronizer.ConditionObject;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.compiler.serviceprovider.GraalUnsafeAccess;
import org.graalvm.compiler.word.BarrieredAccess;
import org.graalvm.nativeimage.IsolateThread;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.WeakIdentityHashMap;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.RestrictHeapAccess.Access;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.ThreadStatus;
import com.oracle.svm.core.thread.ThreadingSupportImpl;
import com.oracle.svm.core.thread.VMOperationControl;
import com.oracle.svm.core.util.VMError;

import sun.misc.Unsafe;

//Checkstyle resume

/**
 * Implementation of synchronized-related operations.
 * <p>
 * Most objects used in synchronization operations have a dedicated memory in the object to store a
 * {@link ReentrantLock}. The static analysis finds out which classes are used for synchronization
 * (and thus need a monitor) and assigns a monitor offset to point to the {@link #getMonitorOffset
 * slot for the monitor}. The monitor is implemented with a {@link ReentrantLock}.
 * <p>
 * There are a few exceptions: {@link String} and {@link DynamicHub} objects never have monitor
 * fields because we want instances in the image heap to be immutable. Arrays never have monitor
 * fields because it would increase the size of every array and it is not possible to distinguish
 * between arrays with different header sizes. See
 * UniverseBuilder.canHaveMonitorFields(AnalysisType) for details.
 * <p>
 * Synchronization on {@link String}, arrays, and other types not detected by the static analysis
 * (like synchronization via JNI) fall back to a monitor stored in {@link #additionalMonitors}.
 * <p>
 * {@link Condition} objects are used to implement {@link #wait()} and {@link #notify()}. When an
 * object monitor needs a condition object, it is atomically swapped into its
 * {@link Target_java_util_concurrent_locks_ReentrantLock_NonfairSync#objectMonitorCondition} field.
 */
public class MultiThreadedMonitorSupport extends MonitorSupport {

    private static final Unsafe UNSAFE = GraalUnsafeAccess.getUnsafe();

    /**
     * {@link Target_java_util_concurrent_locks_ReentrantLock_NonfairSync#objectMonitorCondition}
     * marker to indicate that the associated lock is an object monitor, but does not have a
     * Condition yet. This marker value is needed to identify monitor conditions for
     * {@link #maybeAdjustNewParkStatus}.
     */
    static final ConditionObject MONITOR_WITHOUT_CONDITION = (ConditionObject) new ReentrantLock().newCondition();

    /** Substituted in {@link Target_com_oracle_svm_core_monitor_MultiThreadedMonitorSupport} */
    private static long SYNC_MONITOR_CONDITION_FIELD_OFFSET = -1;

    /**
     * Secondary storage for monitor slots. Synchronized to prevent concurrent access and
     * modification.
     */
    private final Map<Object, ReentrantLock> additionalMonitors = new WeakIdentityHashMap<>();
    private final ReentrantLock additionalMonitorsLock = new ReentrantLock();

    @Override
    public int maybeAdjustNewParkStatus(int status) {
        Object blocker = LockSupport.getBlocker(Thread.currentThread());
        if (isMonitorCondition(blocker)) {
            // Blocked on one of the condition objects we use to implement Object.wait()
            if (status == ThreadStatus.PARKED_TIMED) {
                return ThreadStatus.IN_OBJECT_WAIT_TIMED;
            }
            return ThreadStatus.IN_OBJECT_WAIT;
        } else if (isMonitorLockSynchronizer(blocker)) { // Blocked directly on the lock
            return ThreadStatus.BLOCKED_ON_MONITOR_ENTER;
        }
        return status;
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Avoid stack overflow error before yellow zone has been activated", calleeMustBe = false)
    private static void slowPathMonitorEnter(Object obj) {
        /*
         * A stack overflow error in the locking code would be reported as a fatal error, since
         * there must not be any exceptions flowing out of the monitor code. Enabling the yellow
         * zone prevents stack overflows.
         */
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        ThreadingSupportImpl.pauseRecurringCallback("No exception must flow out of the monitor code.");
        VMOperationControl.guaranteeOkayToBlock("No Java synchronization must be performed within a VMOperation: if the object is already locked, the VM is deadlocked");
        try {
            singleton().monitorEnter(obj);

        } catch (Throwable ex) {
            /*
             * The foreign call from snippets to this method does not have an exception edge. So we
             * could miss an exception handler if we unwind an exception from this method.
             *
             * The only exception that the monitorenter bytecode is specified to throw is a
             * NullPointerException, and the null check already happens beforehand. So any exception
             * would be surprising to users anyway.
             *
             * Finally, it would not be clear whether the monitor is locked or unlocked in case of
             * an exception.
             */
            throw VMError.shouldNotReachHere("Unexpected exception in MonitorSupport.monitorEnter", ex);

        } finally {
            ThreadingSupportImpl.resumeRecurringCallbackAtNextSafepoint();
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    protected static final String NO_LONGER_UNINTERRUPTIBLE = "The monitor snippet slow path is uninterruptible to avoid stack overflow errors being thrown. Now the yellow zone is enabled and we are no longer uninterruptible, and allocation is allowed again too";

    @RestrictHeapAccess(reason = NO_LONGER_UNINTERRUPTIBLE, overridesCallers = true, access = Access.UNRESTRICTED)
    @Override
    public void monitorEnter(Object obj) {
        ReentrantLock lockObject = getOrCreateMonitor(obj, true);
        lockObject.lock();
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Avoid stack overflow error before yellow zone has been activated", calleeMustBe = false)
    private static void slowPathMonitorExit(Object obj) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        ThreadingSupportImpl.pauseRecurringCallback("No exception must flow out of the monitor code.");
        try {
            singleton().monitorExit(obj);

        } catch (Throwable ex) {
            /*
             * The foreign call from snippets to this method does not have an exception edge. So we
             * could miss an exception handler if we unwind an exception from this method.
             *
             * Graal enforces structured locking and unlocking. This is a restriction compared to
             * the Java Virtual Machine Specification, but it ensures that we never need to throw an
             * IllegalMonitorStateException.
             */
            throw VMError.shouldNotReachHere("Unexpected exception in MonitorSupport.monitorExit", ex);

        } finally {
            ThreadingSupportImpl.resumeRecurringCallbackAtNextSafepoint();
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @RestrictHeapAccess(reason = NO_LONGER_UNINTERRUPTIBLE, overridesCallers = true, access = Access.UNRESTRICTED)
    @Override
    public void monitorExit(Object obj) {
        ReentrantLock lockObject = getOrCreateMonitor(obj, true);
        lockObject.unlock();
    }

    @Override
    public void lockRematerializedObject(Object obj, IsolateThread lockingThread, int recursionDepth) {
        assert obj != null;
        int monitorOffset = getMonitorOffset(obj);

        ReentrantLock newMonitor = newLockedMonitorForThread(lockingThread, recursionDepth);
        Object existingMonitor;

        if (monitorOffset != 0) {
            existingMonitor = UNSAFE.getAndSetObject(obj, monitorOffset, newMonitor);
        } else {
            additionalMonitorsLock.lock();
            try {
                existingMonitor = additionalMonitors.put(obj, newMonitor);
            } finally {
                additionalMonitorsLock.unlock();
            }
        }
        VMError.guarantee(existingMonitor == null, "Rematerialized object was already locked");
    }

    @Override
    public boolean holdsLock(Object obj) {
        ReentrantLock lockObject = getOrCreateMonitor(obj, false);
        return lockObject != null && lockObject.isHeldByCurrentThread();
    }

    @SuppressFBWarnings(value = {"WA_AWAIT_NOT_IN_LOOP"}, justification = "This method is a wait implementation.")
    @Override
    protected void doWait(Object obj, long timeoutMillis) throws InterruptedException {
        /*
         * Ensure that the current thread holds the lock. Required by the specification of
         * Object.wait, and also required for our implementation.
         */
        ReentrantLock lock = ensureLocked(obj);
        Condition condition = getOrCreateCondition(lock, true);
        if (timeoutMillis == 0L) {
            condition.await();
        } else {
            condition.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void notify(Object obj, boolean notifyAll) {
        /* Make sure the current thread holds the lock on the receiver. */
        ReentrantLock lock = ensureLocked(obj);
        /* Find the wait/notify condition of the receiver. */
        Condition condition = getOrCreateCondition(lock, false);
        /* If the receiver does not have a condition, then it has not been waited on. */
        if (condition != null) {
            if (notifyAll) {
                condition.signalAll();
            } else {
                condition.signal();
            }
        }
    }

    /** Returns the lock of the object. */
    protected ReentrantLock ensureLocked(Object obj) {
        ReentrantLock lockObject = getOrCreateMonitor(obj, true);
        if (!lockObject.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Receiver is not locked by the current thread.");
        }
        return lockObject;
    }

    protected static int getMonitorOffset(Object obj) {
        return DynamicHub.fromClass(obj.getClass()).getMonitorOffset();
    }

    protected final ReentrantLock getOrCreateMonitor(Object obj, boolean createIfNotExisting) {
        assert obj != null;
        int monitorOffset = getMonitorOffset(obj);
        if (monitorOffset != 0) {
            /* The common case: pointer to the monitor reserved in the object. */
            return getOrCreateMonitorFromObject(obj, createIfNotExisting, monitorOffset);
        } else {
            /* No memory reserved for a lock in the object, fall back to secondary storage. */
            return getOrCreateMonitorFromMap(obj, createIfNotExisting);
        }
    }

    protected ReentrantLock getOrCreateMonitorFromObject(Object obj, boolean createIfNotExisting, int monitorOffset) {
        ReentrantLock existingMonitor = KnownIntrinsics.convertUnknownValue(BarrieredAccess.readObject(obj, monitorOffset), ReentrantLock.class);
        if (existingMonitor != null || !createIfNotExisting) {
            assert existingMonitor == null || isMonitorLock(existingMonitor);
            return existingMonitor;
        }
        /* Atomically put a new lock in place of the null at the monitorOffset. */
        ReentrantLock newMonitor = newMonitorLock();
        if (UNSAFE.compareAndSwapObject(obj, monitorOffset, null, newMonitor)) {
            return newMonitor;
        }
        /* We lost the race, use the lock some other thread installed. */
        return KnownIntrinsics.convertUnknownValue(BarrieredAccess.readObject(obj, monitorOffset), ReentrantLock.class);
    }

    protected ReentrantLock getOrCreateMonitorFromMap(Object obj, boolean createIfNotExisting) {
        assert obj.getClass() != Target_java_lang_ref_ReferenceQueue_Lock.class : "ReferenceQueue.Lock must have a monitor field or we can deadlock accessing WeakIdentityHashMap below";

        /*
         * Lock the monitor map and maybe add a monitor for this object. This serialization might be
         * a scalability problem.
         */
        additionalMonitorsLock.lock();
        try {
            ReentrantLock existingMonitor = additionalMonitors.get(obj);
            if (existingMonitor != null || !createIfNotExisting) {
                assert existingMonitor == null || isMonitorLock(existingMonitor);
                return existingMonitor;
            }
            ReentrantLock newMonitor = newMonitorLock();
            ReentrantLock previousEntry = additionalMonitors.put(obj, newMonitor);
            VMError.guarantee(previousEntry == null, "Replaced monitor in secondary storage map");
            return newMonitor;
        } finally {
            additionalMonitorsLock.unlock();
        }
    }

    protected static ReentrantLock newMonitorLock() {
        ReentrantLock newMonitor = new ReentrantLock();
        Target_java_util_concurrent_locks_ReentrantLock lock = SubstrateUtil.cast(newMonitor, Target_java_util_concurrent_locks_ReentrantLock.class);
        Target_java_util_concurrent_locks_ReentrantLock_NonfairSync sync = SubstrateUtil.cast(lock.sync, Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class);
        sync.objectMonitorCondition = SubstrateUtil.cast(MONITOR_WITHOUT_CONDITION, Target_java_util_concurrent_locks_AbstractQueuedSynchronizer_ConditionObject.class);
        assert isMonitorLock(newMonitor);
        return newMonitor;
    }

    /**
     * Creates a new {@link ReentrantLock} that is locked by the provided thread. This requires
     * patching of internal state, since there is no public API in {@link ReentrantLock} to do that
     * (for a good reason, because it is a highly unusual operation).
     */
    protected static ReentrantLock newLockedMonitorForThread(IsolateThread isolateThread, int recursionDepth) {
        ReentrantLock result = newMonitorLock();
        for (int i = 0; i < recursionDepth; i++) {
            result.lock();
        }

        Target_java_util_concurrent_locks_ReentrantLock lock = SubstrateUtil.cast(result, Target_java_util_concurrent_locks_ReentrantLock.class);
        Target_java_util_concurrent_locks_AbstractOwnableSynchronizer sync = SubstrateUtil.cast(lock.sync, Target_java_util_concurrent_locks_AbstractOwnableSynchronizer.class);

        assert sync.getExclusiveOwnerThread() == Thread.currentThread() : "Must be locked by current thread";
        sync.setExclusiveOwnerThread(JavaThreads.fromVMThread(isolateThread));

        return result;
    }

    protected static boolean isMonitorLock(ReentrantLock lock) {
        return lock != null && isMonitorLockSynchronizer(SubstrateUtil.cast(lock, Target_java_util_concurrent_locks_ReentrantLock.class).sync);
    }

    protected static boolean isMonitorLockSynchronizer(Object obj) {
        if (obj != null && obj.getClass() == Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class) {
            Target_java_util_concurrent_locks_ReentrantLock_NonfairSync sync = SubstrateUtil.cast(obj, Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class);
            return sync.objectMonitorCondition != null; // contains marker or actual condition
        }
        return false;
    }

    public ReentrantLock getMonitorForTesting(Object obj) {
        return getOrCreateMonitor(obj, true);
    }

    protected ConditionObject getOrCreateCondition(ReentrantLock monitorLock, boolean createIfNotExisting) {
        assert isMonitorLock(monitorLock);
        Target_java_util_concurrent_locks_ReentrantLock lock = SubstrateUtil.cast(monitorLock, Target_java_util_concurrent_locks_ReentrantLock.class);
        Target_java_util_concurrent_locks_ReentrantLock_NonfairSync sync = SubstrateUtil.cast(lock.sync, Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class);
        ConditionObject existingCondition = SubstrateUtil.cast(sync.objectMonitorCondition, ConditionObject.class);
        if (existingCondition == MONITOR_WITHOUT_CONDITION) {
            existingCondition = null;
        }
        if (existingCondition != null || !createIfNotExisting) {
            assert existingCondition == null || isMonitorCondition(existingCondition);
            return existingCondition;
        }
        ConditionObject newCondition = (ConditionObject) monitorLock.newCondition();
        if (!UNSAFE.compareAndSwapObject(sync, SYNC_MONITOR_CONDITION_FIELD_OFFSET, MONITOR_WITHOUT_CONDITION, newCondition)) {
            newCondition = SubstrateUtil.cast(sync.objectMonitorCondition, ConditionObject.class);
            assert isMonitorCondition(newCondition) : "race winner must have installed valid condition";
        }
        return newCondition;
    }

    protected static boolean isMonitorCondition(Object obj) {
        if (obj != null && obj.getClass() == Target_java_util_concurrent_locks_AbstractQueuedSynchronizer_ConditionObject.class) {
            Target_java_util_concurrent_locks_AbstractQueuedSynchronizer enclosing = SubstrateUtil.cast(obj, Target_java_util_concurrent_locks_AbstractQueuedSynchronizer_ConditionObject.class).this$0;
            if (enclosing.getClass() == (Class<?>) Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class) {
                Target_java_util_concurrent_locks_ReentrantLock_NonfairSync sync = SubstrateUtil.cast(enclosing, Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class);
                return obj == sync.objectMonitorCondition;
            }
        }
        return false;
    }
}

@TargetClass(value = AbstractOwnableSynchronizer.class)
final class Target_java_util_concurrent_locks_AbstractOwnableSynchronizer {

    @Alias
    protected native Thread getExclusiveOwnerThread();

    @Alias
    protected native void setExclusiveOwnerThread(Thread thread);
}

@TargetClass(value = ReentrantLock.class, innerClass = "Sync")
final class Target_java_util_concurrent_locks_ReentrantLock_Sync {
}

@TargetClass(value = ReentrantLock.class, innerClass = "NonfairSync")
final class Target_java_util_concurrent_locks_ReentrantLock_NonfairSync {
    /**
     * If this is a monitor's synchronizer, either
     * {@link MultiThreadedMonitorSupport#MONITOR_WITHOUT_CONDITION} to mark it as part of a monitor
     * that currently has no condition variable, or otherwise, a specific {@link ConditionObject}
     * that provides conditional waiting for this monitor. If {@code null}, not associated with a
     * monitor.
     */
    @Inject @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset) //
    volatile Target_java_util_concurrent_locks_AbstractQueuedSynchronizer_ConditionObject objectMonitorCondition;
}

@TargetClass(MultiThreadedMonitorSupport.class)
final class Target_com_oracle_svm_core_monitor_MultiThreadedMonitorSupport {
    @Alias @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.FieldOffset, name = "objectMonitorCondition", declClass = Target_java_util_concurrent_locks_ReentrantLock_NonfairSync.class) //
    static long SYNC_MONITOR_CONDITION_FIELD_OFFSET;
}

@TargetClass(ReentrantLock.class)
final class Target_java_util_concurrent_locks_ReentrantLock {
    @Alias//
    Target_java_util_concurrent_locks_ReentrantLock_Sync sync;
}

@TargetClass(AbstractQueuedSynchronizer.class)
final class Target_java_util_concurrent_locks_AbstractQueuedSynchronizer {
}

@TargetClass(value = ConditionObject.class)
final class Target_java_util_concurrent_locks_AbstractQueuedSynchronizer_ConditionObject {
    /** Enclosing {@link AbstractQueuedSynchronizer} of this nested class. */
    @Alias Target_java_util_concurrent_locks_AbstractQueuedSynchronizer this$0;
}

@TargetClass(value = ReferenceQueue.class, innerClass = "Lock")
final class Target_java_lang_ref_ReferenceQueue_Lock {
}
