package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WorkspaceStressTest {
    @Test fun twentyThousandMixedEditsPreserveIdentityAndCancellation() {
        val random=Random(4907)
        val keys=(0 until 1000).map { "package.$it" }; val available=keys.toSet()
        var saved=Workspace((0 until 8).map { row -> AppShelf("row$row","Row $row",keys.filterIndexed { i,_ -> i%8==row }) })
        repeat(20000) { iteration ->
            val before=saved
            val key=keys[random.nextInt(keys.size)]
            val changed=when(iteration%6) {
                0 -> saved.moveApp(key,horizontal=if(random.nextBoolean()) 1 else -1,available=available)
                1 -> saved.moveApp(key,vertical=if(random.nextBoolean()) 1 else -1,available=available)
                2 -> saved.hideApp(key)
                3 -> saved.restoreApp(key)
                4 -> saved.moveRow("row${random.nextInt(8)}",if(random.nextBoolean()) 1 else -1)
                else -> saved.discover(keys.shuffled(random))
            }
            saved=if(iteration%7==0) before else changed
            val owned=saved.rows.flatMap { it.appKeys }
            assertEquals(1000,owned.size);assertEquals(available,owned.toSet())
            assertEquals(saved.hiddenApps.size,saved.hiddenApps.toSet().size)
            assertEquals(8,saved.rows.map { it.id }.toSet().size)
            if(iteration%7==0) assertSame(before,saved)
        }
    }
}
