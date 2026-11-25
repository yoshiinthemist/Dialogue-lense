package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CoverageInterval
import com.example.data.model.CoverageStatus
import com.example.data.project.DemoScenarioFactory
import com.example.data.project.ProjectStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ProjectPersistenceTest {
    @Test fun savedProjectsRoundTripAcrossUpdatesOnOldestSupportedAndroid() = runTest {
        val store = ProjectStore(ApplicationProvider.getApplicationContext<Context>())
        val project = DemoScenarioFactory.createDemoProject().copy(id = "library-video")
        val updated = project.copy(lastPlaybackPositionMs = 2500, coverageIntervals = listOf(
            CoverageInterval(0, 3500, CoverageStatus.VERIFIED_DIALOGUE)
        ))
        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.saveProject(project) }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { store.saveProject(updated) }
        first.join()
        second.join()
        assertEquals(updated, store.listSavedProjects().single { it.id == updated.id })
        val invalid = store.serializeProject(project.copy(id = "../bad"))
        assertNull(store.deserializeProject(invalid))
        store.deleteProject(project.id)
        assertTrue(store.listSavedProjects().none { it.id == project.id })
    }
}
