package io.github.aitm.pleosapp.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GleoContractTest {
    @Test fun handlesObservedGleoPackagePrefixAndLegacyAdbActions() {
        for (pkg in listOf("io.github.aitm.pleosapp", "io.github.aitm.pleosapp.demo")) {
            for (function in listOf("START_ROADSIDE_SUPPORT", "REPORT_OCCUPANT_STATUS", "GET_ROADSIDE_STATUS")) {
                assertEquals(function, GleoContract.functionName("$pkg.$function", pkg))
                assertEquals(function, GleoContract.functionName(function, pkg))
            }
        }
    }

    @Test fun ignoresUnknownAndForeignPackageActions() {
        val pkg = "io.github.aitm.pleosapp"
        for (action in listOf(null, "android.intent.action.MAIN", "$pkg.OPEN_TRUNK", "other.GET_ROADSIDE_STATUS", "$pkg.demo.GET_ROADSIDE_STATUS")) {
            assertNull(GleoContract.functionName(action, pkg))
        }
    }
}
