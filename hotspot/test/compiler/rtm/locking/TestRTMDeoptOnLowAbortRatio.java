/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 *
 */

/**
 * @test
 * @bug 8031320
 * @summary Verify that on low abort ratio method will be recompiled.
 * @library /testlibrary /test/lib /
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @build TestRTMDeoptOnLowAbortRatio
 * @run driver ClassFileInstaller sun.hotspot.WhiteBox
 *                                sun.hotspot.WhiteBox$WhiteBoxPermission
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *                   -XX:+WhiteBoxAPI TestRTMDeoptOnLowAbortRatio
 */

import java.util.List;
import jdk.test.lib.*;
import jdk.test.lib.cli.CommandLineOptionTest;
import jdk.test.lib.cli.predicate.AndPredicate;
import compiler.testlibrary.rtm.*;
import compiler.testlibrary.rtm.predicate.SupportedCPU;
import compiler.testlibrary.rtm.predicate.SupportedVM;
import jdk.internal.misc.Unsafe;

/**
 * Test verifies that low abort ratio method will be deoptimized with
 * <i>rtm_state_change</i> reason and will continue to use RTM-based lock
 * elision after that.
 * This test make asserts on total locks count done by compiled method,
 * so in order to avoid issue with retriable locks -XX:RTMRetryCount=0 is used.
 * For more details on that issue see {@link TestUseRTMAfterLockInflation}.
 */
public class TestRTMDeoptOnLowAbortRatio extends CommandLineOptionTest {
    private static final long LOCKING_THRESHOLD = 100L;
    private static final long ABORT_THRESHOLD = LOCKING_THRESHOLD / 2L;

    private TestRTMDeoptOnLowAbortRatio() {
        super(new AndPredicate(new SupportedCPU(), new SupportedVM()));
    }

    @Override
    protected void runTestCases() throws Throwable {
        verifyRTMDeopt(false);
        verifyRTMDeopt(true);
    }

    private void verifyRTMDeopt(boolean useStackLock) throws Throwable {
        CompilableTest test = new Test();
        String logFileName = String.format("rtm_deopt_%s_stack_lock.xml",
                                           useStackLock ? "use" : "no");

        OutputAnalyzer outputAnalyzer = RTMTestBase.executeRTMTest(
                logFileName,
                test,
                "-XX:+UseRTMDeopt",
                CommandLineOptionTest.prepareBooleanFlag("UseRTMForStackLocks",
                        useStackLock),
                CommandLineOptionTest.prepareNumericFlag("RTMLockingThreshold",
                        TestRTMDeoptOnLowAbortRatio.LOCKING_THRESHOLD),
                CommandLineOptionTest.prepareNumericFlag("RTMAbortThreshold",
                        TestRTMDeoptOnLowAbortRatio.ABORT_THRESHOLD),
                "-XX:RTMAbortRatio=100",
                "-XX:CompileThreshold=1",
                "-XX:RTMRetryCount=0",
                "-XX:RTMTotalCountIncrRate=1",
                "-XX:+PrintPreciseRTMLockingStatistics",
                Test.class.getName(),
                Boolean.toString(!useStackLock)
        );

        outputAnalyzer.shouldHaveExitValue(0);

        int firedTraps = RTMTestBase.firedRTMStateChangeTraps(logFileName);

        Asserts.assertEQ(firedTraps, 1,
                        "Expected to get only one deoptimization due to rtm"
                        + " state change");

        List<RTMLockingStatistics> statistics = RTMLockingStatistics.fromString(
                test.getMethodWithLockName(), outputAnalyzer.getOutput());

        Asserts.assertEQ(statistics.size(), 2,
                         "VM output should contain two RTM locking "
                         + "statistics entries for method "
                         + test.getMethodWithLockName());

        RTMLockingStatistics statisticsBeforeDeopt = null;

        for (RTMLockingStatistics s : statistics) {
            if (s.getTotalLocks()
                    == TestRTMDeoptOnLowAbortRatio.LOCKING_THRESHOLD) {
                Asserts.assertNull(statisticsBeforeDeopt,
                        "Only one abort was expected during test run");
                statisticsBeforeDeopt = s;
            }
        }

        Asserts.assertNotNull(statisticsBeforeDeopt,
                "After LockThreshold was reached, method should be recompiled "
                + "with rtm lock eliding.");
    }

    public static class Test implements CompilableTest {
        private static final Unsafe UNSAFE = Utils.getUnsafe();
        private final Object monitor = new Object();

        @Override
        public String getMethodWithLockName() {
            return this.getClass().getName() + "::forceAbort";
        }

        @Override
        public String[] getMethodsToCompileNames() {
            return new String[] { getMethodWithLockName() };
        }

        public void forceAbort(boolean abort) {
            synchronized(monitor) {
                if (abort) {
                    Test.UNSAFE.addressSize();
                }
            }
        }

        /**
         * Usage:
         * Test &lt;inflate monitor&gt;
         */
        public static void main(String args[]) throws Throwable {
            Asserts.assertGTE(args.length, 1, "One argument required.");
            Test t = new Test();
            boolean shouldBeInflated = Boolean.valueOf(args[0]);
            if (shouldBeInflated) {
                AbortProvoker.inflateMonitor(t.monitor);
            }
            for (int i = 0; i < AbortProvoker.DEFAULT_ITERATIONS; i++) {
                AbortProvoker.verifyMonitorState(t.monitor, shouldBeInflated);
                t.forceAbort(i >= TestRTMDeoptOnLowAbortRatio.ABORT_THRESHOLD);
            }
        }
    }

    public static void main(String args[]) throws Throwable {
        new TestRTMDeoptOnLowAbortRatio().test();
    }
}
