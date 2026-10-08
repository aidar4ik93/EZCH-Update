package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class CenterPressTest {
    private fun CenterPress.down(t: Long = 1000, repeat: Int = 0, moving: Boolean = false) = handle(23,true,repeat,t,false,moving)
    private fun CenterPress.up(t: Long = 1100, moving: Boolean = false, canceled: Boolean = false) = handle(23,false,0,t,canceled,moving)
    @Test fun shortPressLaunchesOnlyOnPairedRelease() {
        val press=CenterPress(); assertEquals(CenterPress.Action.NONE,press.down())
        assertEquals(CenterPress.Action.CLICK,press.up()); assertEquals(CenterPress.Action.NONE,press.up())
    }
    @Test fun orphanReleaseCannotLaunchOrEnterEdit() { assertEquals(CenterPress.Action.NONE,CenterPress().up(10000)) }
    @Test fun canceledReleaseDoesNothing() { val p=CenterPress();p.down();assertEquals(CenterPress.Action.NONE,p.up(canceled=true)) }
    @Test fun longPressWithRepeatNeverConfirmsOrLaunchesOnRelease() {
        val p=CenterPress();p.down();assertEquals(CenterPress.Action.LONG_PRESS,p.down(1500,1))
        assertEquals(CenterPress.Action.NONE,p.down(1600,2,moving=true))
        assertEquals(CenterPress.Action.NONE,p.up(1800,moving=true))
    }
    @Test fun longPressWithoutRepeatWorksOnce() { val p=CenterPress();p.down();assertEquals(CenterPress.Action.LONG_PRESS,p.up(1500));assertEquals(CenterPress.Action.NONE,p.up(1600)) }
    @Test fun confirmationCannotAlsoLaunch() {
        val p=CenterPress();p.down(moving=true);assertEquals(CenterPress.Action.CONFIRM,p.up(moving=true))
        assertEquals(CenterPress.Action.NONE,p.up(moving=false))
    }
    @Test fun modeChangeBetweenDownAndUpDoesNotExecuteOldAction() { val p=CenterPress();p.down();assertEquals(CenterPress.Action.NONE,p.up(moving=true)) }
    @Test fun focusResetRejectsPendingRelease() { val p=CenterPress();p.down();p.reset();assertEquals(CenterPress.Action.NONE,p.up()) }
    @Test fun unrelatedKeyCannotCompletePress() {
        val p=CenterPress();p.down();assertEquals(CenterPress.Action.NONE,p.handle(66,false,0,1100,false,false))
        assertEquals(CenterPress.Action.CLICK,p.up())
    }
    @Test fun hundredThousandPressesDoNotDuplicateActions() {
        val p=CenterPress()
        repeat(100000) { n ->
            val start=n*2000L
            p.down(start)
            if(n%2==0) {
                assertEquals(CenterPress.Action.LONG_PRESS,p.down(start+500,1))
                repeat(5) { assertEquals(CenterPress.Action.NONE,p.down(start+600+it,2+it,true)) }
                assertEquals(CenterPress.Action.NONE,p.up(start+1200,true))
            } else assertEquals(CenterPress.Action.CLICK,p.up(start+80))
            assertEquals(CenterPress.Action.NONE,p.up(start+1400))
        }
    }
    @Test fun flaggedLongPressWithEqualTimeNeverClicks() {
        val p=CenterPress();p.handle(23,true,0,1000,false,false)
        assertEquals(CenterPress.Action.LONG_PRESS,p.handle(23,true,1,1000,false,false,true))
        assertEquals(CenterPress.Action.NONE,p.handle(23,false,0,1000,false,false))
    }
}
