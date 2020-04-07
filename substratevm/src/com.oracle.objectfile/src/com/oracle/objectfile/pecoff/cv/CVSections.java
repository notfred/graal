/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, Red Hat Inc. All rights reserved.
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

package com.oracle.objectfile.pecoff.cv;

import com.oracle.objectfile.debugentry.DebugInfoBase;
import com.oracle.objectfile.pecoff.PECoffMachine;

import java.nio.ByteOrder;

/**
 * CVSections is a container class for all the CodeView sections to be emitted in the object file.
 * Currently, that will be .debug$S (CVSymbolSectionImpl) and .debug$T (CVTypeSectionImpl)
 * Common data (useful to more than one CodeView section) goes here, mostly that gathered by calls to installDebugInfo->addRange() and installDebugInfo->addSubRange()
 */
public final class CVSections extends DebugInfoBase {

    @SuppressWarnings("unused") private PECoffMachine machine;
    private CVSymbolSectionImpl cvSymbolSection;
    private CVTypeSectionImpl cvTypeSection;

    public CVSections(PECoffMachine targetMachine, ByteOrder byteOrder) {
        super(byteOrder);
        machine = targetMachine;
        cvSymbolSection = new CVSymbolSectionImpl(this);
        cvTypeSection   = new CVTypeSectionImpl();
    }

    public CVSymbolSectionImpl getCVSymbolSection() {
        return cvSymbolSection;
    }

    public CVTypeSectionImpl getCVTypeSection() {
        return cvTypeSection;
    }

}
