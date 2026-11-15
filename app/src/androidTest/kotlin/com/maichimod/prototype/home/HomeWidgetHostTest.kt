package com.maichimod.prototype.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.MainActivity
import com.maichimod.prototype.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeWidgetHostTest {
    @Volatile private var received: List<WidgetField> = emptyList()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val spec = WidgetSpec("test", ComponentName("com.maichimod.prototype.test", TestInformationWidget::class.java.name), 116005, 6105, 280, 180, false)
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).readBytes()
    }

    @Test fun canceledAndAbandonedBindingsDeleteOnlyTheirOwnIds() {
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = HomeInfoStore(activity, "home-widget-test")
                context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                var request: Intent? = null
                val host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { intent, _ -> request = intent }, {}, {})
                host.connect()
                assertNotNull(request)
                val canceled = store.pendingId(spec.key)
                assertTrue(canceled >= 0)
                host.onActivityResult(spec.requestCode, 0, null)
                assertEquals(-1, store.pendingId(spec.key))
                assertFalse(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(canceled))
                host.connect()
                val abandoned = store.pendingId(spec.key)
                assertTrue(abandoned >= 0)
                host.close()
                val restored = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> }, {}, {})
                assertFalse(AppWidgetHost(activity, spec.hostId).appWidgetIds.contains(abandoned))
                assertEquals(-1, store.pendingId(spec.key))
                restored.onActivityResult(spec.requestCode, -1, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, abandoned))
                assertEquals(-1, store.boundId(spec.key))
                restored.disconnect(); restored.close(); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
            }
        }
    }

    @Test fun hiddenHostReceivesUpdatesStopsAndReusesBoundIdAcrossRecreation() {
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        lateinit var host: HomeWidgetHost
        lateinit var store: HomeInfoStore
        var count: Int? = null
        var boundId = -1
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    store = HomeInfoStore(activity, "home-widget-test"); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                    host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> fail("Grant should permit binding") }, {}, { fields ->
                        received = fields
                        count = fields.singleOrNull { it.name == "tv_pkg_num" }?.text?.toIntOrNull()
                    })
                    host.start(); host.connect()
                    boundId = store.boundId(spec.key)
                    assertTrue(boundId >= 0)
                }
                try { waitFor { count == 2 } } catch (failure: AssertionError) {
                    var tree = ""
                    scenario.onActivity {
                        val frame = host.javaClass.getDeclaredField("frame").apply { isAccessible = true }.get(host) as android.view.View
                        fun dump(view: android.view.View) {
                            tree += "${view.javaClass.simpleName} #${view.id} ${runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()} ${(view as? android.widget.TextView)?.text}; "
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) dump(view.getChildAt(index))
                        }
                        dump(frame)
                    }
                    throw AssertionError("${failure.message}; tree: $tree", failure)
                }
                context.sendBroadcast(Intent("com.maichimod.prototype.test.CHANGE_WIDGET").setComponent(spec.provider).putExtra("number", "3"))
                waitFor { count == 3 }
                scenario.onActivity { activity ->
                    host.close(); count = null
                    host = HomeWidgetHost(activity, spec, store, activity.findViewById(R.id.app_root), { _, _ -> fail("Must reuse authorization") }, {}, { fields ->
                        received = fields
                        count = fields.singleOrNull { it.name == "tv_pkg_num" }?.text?.toIntOrNull()
                    })
                    host.start()
                    assertEquals(boundId, store.boundId(spec.key))
                }
                waitFor { count == 3 }
                scenario.onActivity { host.stop(); count = null }
                context.sendBroadcast(Intent("com.maichimod.prototype.test.CHANGE_WIDGET").setComponent(spec.provider).putExtra("number", "4"))
                Thread.sleep(600)
                assertNull(count)
                scenario.onActivity { host.start() }
                waitFor { count == 4 }
                scenario.onActivity {
                    host.disconnect(); host.close(); context.getSharedPreferences("home-widget-test", 0).edit().clear().commit()
                    assertFalse(AppWidgetHost(it, spec.hostId).appWidgetIds.contains(boundId))
                }
            }
        } finally { shell("appwidget revokebind --package ${context.packageName} --user 0") }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for hosted widget data: $received", predicate())
    }
}
