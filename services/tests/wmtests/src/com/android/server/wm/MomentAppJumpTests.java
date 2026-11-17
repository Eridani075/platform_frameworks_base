/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MOMENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for automatic Moment launches. Run with atest WmTests:MomentAppJumpTests. */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class MomentAppJumpTests extends WindowTestsBase {
    private static final String SOURCE = "com.test.game";
    private static final String TARGET = "com.test.share";
    private static final int SOURCE_UID = 10001;
    private MomentController mMoment;
    private ActivityRecord mSource;
    private ActivityInfo mTarget;

    @Before
    public void setUp() {
        mMoment = mWm.mMomentController;
        mMoment.setEnabledFromSettings(true);
        mMoment.setAppJumpSettingsFromSettings(0, true, SOURCE);
        mSource = new ActivityBuilder(mAtm).setCreateTask(true).setVisible(true)
                .setUid(SOURCE_UID).setComponent(new ComponentName(SOURCE, SOURCE + ".Game"))
                .build();
        mSource.getTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        mTarget = target(TARGET, TARGET + ".Share");
    }

    @Test
    public void selectedSourceLaunchesMomentWithoutChangingOriginalOptions() {
        final ActivityOptions original = ActivityOptions.makeBasic();
        final MomentController.AppJumpLaunch launch = prepare(mTarget, mSource, SOURCE,
                SOURCE_UID, original);
        assertNotNull(launch);
        assertSame(mSource.getTask(), launch.sourceTask);
        assertEquals(WINDOWING_MODE_MOMENT, launch.options.getLaunchWindowingMode());
        assertEquals(0, original.getLaunchWindowingMode());
        assertEquals(WINDOWING_MODE_FULLSCREEN, mSource.getTask().getWindowingMode());
    }

    @Test
    public void disabledOrUnselectedSourceDoesNotLaunchMoment() {
        mMoment.setAppJumpSettingsFromSettings(0, false, SOURCE);
        assertNull(prepare(mTarget));
        mMoment.setAppJumpSettingsFromSettings(0, true, "com.test.other");
        assertNull(prepare(mTarget));
        mMoment.setAppJumpSettingsFromSettings(0, true, SOURCE);
        mMoment.setEnabledFromSettings(false);
        assertNull(prepare(mTarget));
    }

    @Test
    public void samePackageAndSystemAppsAreExcluded() {
        assertNull(prepare(target(SOURCE, SOURCE + ".Details")));
        mTarget.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        assertNull(prepare(mTarget));
        mTarget.applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        assertNull(prepare(mTarget));
    }

    @Test
    public void systemMediatorsAreExcludedEvenWithoutSystemFlag() {
        for (ActivityInfo mediator : new ActivityInfo[] {
                target("com.android.intentresolver", "com.android.intentresolver.ChooserActivity"),
                target("com.android.permissioncontroller", "GrantPermissionsActivity"),
                target("com.android.vpndialogs", "ConfirmDialog"),
                target("com.android.providers.media.module",
                        "com.android.providers.media.photopicker.PhotoPickerActivity"),
                target("com.android.documentsui", "com.android.documentsui.picker.PickActivity")
        }) {
            assertNull(prepare(mediator));
        }
    }

    @Test
    public void chooserSelectionUsesVisibleFullscreenAppBelowIt() {
        final ActivityRecord chooser = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(true).setUid(10002)
                .setComponent(new ComponentName("com.android.intentresolver",
                        "com.android.intentresolver.ChooserActivity")).build();
        mSource.setVisibleRequested(true);
        final MomentController.AppJumpLaunch launch = prepare(mTarget, chooser,
                chooser.packageName, chooser.getUid(), null);
        assertNotNull(launch);
        assertSame(mSource.getTask(), launch.sourceTask);
    }

    @Test
    public void mismatchedCallerAndOtherUserAreExcluded() {
        assertNull(prepare(mTarget, mSource, SOURCE, 10003, null));
        mMoment.setAppJumpSettingsFromSettings(10, true, SOURCE);
        assertNull(prepare(mTarget));
    }

    @Test
    public void landscapeUsesPortraitLaunchBounds() {
        doReturn(new Rect(0, 0, 2000, 1000)).when(mSource.getDisplayContent()).getBounds();
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        assertEquals(new Rect(0, 0, 1000, 2000), launch.options.getLaunchBounds());
    }

    @Test
    public void explicitTaskOrHomeLaunchIsExcluded() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(mSource.getTask().mTaskId);
        assertNull(prepare(mTarget, mSource, SOURCE, SOURCE_UID, options));
        final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        assertNull(mMoment.prepareAppJumpMomentLaunchLocked(home, mTarget, mSource,
                SOURCE, SOURCE_UID, 0, null, null, null));
    }

    @Test
    public void sourceTaskAndUntrackedMomentTaskCannotBeReused() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        assertFalse(mMoment.canReuseAppJumpTaskLocked(launch, mSource.getTask()));
        final ActivityRecord other = new ActivityBuilder(mAtm).setCreateTask(true)
                .setComponent(new ComponentName(TARGET, TARGET + ".Main")).build();
        other.getTask().setWindowingMode(WINDOWING_MODE_MOMENT);
        assertFalse(mMoment.canReuseAppJumpTaskLocked(launch, other.getTask()));
    }

    @Test
    public void failedLaunchRestoresExistingTaskWithoutFinishingItsHistory() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        final ActivityRecord main = new ActivityBuilder(mAtm).setCreateTask(true)
                .setOnTop(false).setComponent(new ComponentName(TARGET, TARGET + ".Main"))
                .build();
        final Task task = main.getTask();
        final ActivityRecord history = new ActivityBuilder(mAtm).setTask(task).setOnTop(false)
                .setComponent(new ComponentName(TARGET, TARGET + ".Chat")).build();
        assertTrue(mMoment.canReuseAppJumpTaskLocked(launch, task));
        mMoment.onAppJumpTaskResolvedLocked(launch, task);
        assertTrue(launch.originalActivities.contains(main));
        assertTrue(launch.originalActivities.contains(history));

        task.setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchFailedLocked(launch);

        assertTrue(task.isAttached());
        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        assertSame(task, main.getTask());
        assertSame(task, history.getTask());
        assertFalse(main.finishing);
        assertFalse(history.finishing);
    }

    @Test
    public void callbackUsesOriginalFullscreenTaskAndStopsAfterManualExpansion() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        final ActivityRecord entry = new ActivityBuilder(mAtm).setCreateTask(true)
                .setComponent(new ComponentName(TARGET, TARGET + ".Share")).build();
        final Task task = entry.getTask();
        task.setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchSucceededLocked(launch, entry);

        final ActivityInfo callback = target(SOURCE, SOURCE + ".wxapi.WXEntryActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, entry,
                TARGET, entry.getUid(), null);
        assertNotNull(returning);
        assertTrue(returning.isReturnToSource());
        assertSame(mSource.getTask(), returning.sourceTask);
        assertEquals(WINDOWING_MODE_FULLSCREEN, returning.options.getLaunchWindowingMode());
        doReturn(task).when(mRootWindowContainer).getTopDisplayFocusedRootTask();
        final MomentController.AppJumpLaunch contextCallback = prepare(callback, null,
                TARGET, entry.getUid(), null);
        assertNotNull(contextCallback);
        assertSame(mSource.getTask(), contextCallback.sourceTask);

        mMoment.onTaskLeftMomentWindowingMode(task);
        assertNull(prepare(callback, entry, TARGET, entry.getUid(), null));
    }

    @Test
    public void onlyEmptyCanceledEntryResultIsDeferred() {
        final ActivityRecord entry = createMomentEntry();
        entry.resultTo = mSource;
        entry.requestCode = 10103;
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_OK, null));
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED,
                new Intent("com.test.ACTUAL_RESULT")));
        final ActivityRecord child = new ActivityBuilder(mAtm).setTask(entry.getTask())
                .setComponent(new ComponentName(TARGET, TARGET + ".SharePage")).build();
        child.resultTo = mSource;
        assertFalse(mMoment.deferAppJumpCanceledResultLocked(child, RESULT_CANCELED, null));
        assertTrue(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED, null));
    }

    @Test
    public void manualExpansionKeepsPendingSdkCallbackWithoutClosingFullscreenTask() {
        final ActivityRecord entry = createMomentEntry();
        final Task task = entry.getTask();
        entry.resultTo = mSource;
        entry.requestCode = 10103;
        assertTrue(mMoment.deferAppJumpCanceledResultLocked(entry, RESULT_CANCELED, null));

        mMoment.exitMomentTask(task.mTaskId);

        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        final ActivityInfo callback = target(SOURCE, SOURCE + ".SdkCallbackActivity");
        final MomentController.AppJumpLaunch returning = prepare(callback, entry,
                TARGET, entry.getUid(), null);
        assertNotNull(returning);
        assertTrue(returning.isReturnToSource());
        mMoment.onAppJumpLaunchSucceededLocked(returning, mSource);
        assertTrue(task.isAttached());
        assertEquals(WINDOWING_MODE_FULLSCREEN, task.getWindowingMode());
        assertNull(prepare(callback, entry, TARGET, entry.getUid(), null));
    }

    @Test
    public void emptyTaskWaitsForHandoffUntilSessionIsReleased() {
        final ActivityRecord entry = createMomentEntry();
        final Task task = entry.getTask();
        assertFalse(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
        entry.makeFinishingLocked();
        assertTrue(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
        mMoment.setEnabledFromSettings(false);
        assertFalse(mMoment.shouldDeferEmptyAppJumpTaskRemovalLocked(task));
    }

    private ActivityRecord createMomentEntry() {
        final MomentController.AppJumpLaunch launch = prepare(mTarget);
        assertNotNull(launch);
        final ActivityRecord entry = new ActivityBuilder(mAtm).setCreateTask(true)
                .setComponent(new ComponentName(TARGET, TARGET + ".Share")).build();
        entry.getTask().setWindowingMode(WINDOWING_MODE_MOMENT);
        mMoment.onAppJumpLaunchSucceededLocked(launch, entry);
        return entry;
    }

    private MomentController.AppJumpLaunch prepare(ActivityInfo target) {
        return prepare(target, mSource, SOURCE, SOURCE_UID, null);
    }

    private MomentController.AppJumpLaunch prepare(ActivityInfo target, ActivityRecord source,
            String callingPackage, int callingUid, ActivityOptions options) {
        return mMoment.prepareAppJumpMomentLaunchLocked(new Intent(), target, source,
                callingPackage, callingUid, 0, options, null, null);
    }

    private static ActivityInfo target(String packageName, String className) {
        final ActivityInfo target = new ActivityInfo();
        target.packageName = packageName;
        target.name = className;
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = packageName;
        return target;
    }
}
