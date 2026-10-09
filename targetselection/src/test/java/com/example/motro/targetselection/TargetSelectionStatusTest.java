package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionStatus;

import org.junit.Test;

/** 状态语义测试：确保上层能区分“点到但没有 id”和“什么都没点到”。 */
public class TargetSelectionStatusTest {

    @Test
    public void specStatusesAllExist() {
        // Slice 0 契约：这 7 个状态必须存在，改名/删除都会破坏上层分支
        assertEquals("HIT", TargetSelectionStatus.HIT.name());
        assertEquals("MISS", TargetSelectionStatus.MISS.name());
        assertEquals("HIT_ID_UNKNOWN", TargetSelectionStatus.HIT_ID_UNKNOWN.name());
        assertEquals("OUTSIDE_VIDEO", TargetSelectionStatus.OUTSIDE_VIDEO.name());
        assertEquals("CAPTURE_FAILED", TargetSelectionStatus.CAPTURE_FAILED.name());
        assertEquals("NO_FRAME", TargetSelectionStatus.NO_FRAME.name());
        assertEquals("CANCELLED", TargetSelectionStatus.CANCELLED.name());
    }

    @Test
    public void hitFamilyCoversKnownAndUnknownId() {
        assertTrue(TargetSelectionStatus.HIT.isHit());
        assertTrue(TargetSelectionStatus.HIT_ID_UNKNOWN.isHit());
        assertTrue(TargetSelectionStatus.HIT.hasResolvedId());
        assertFalse(TargetSelectionStatus.HIT_ID_UNKNOWN.hasResolvedId());
    }

    @Test
    public void nonHitStatusesAreNotReportedAsHit() {
        for (TargetSelectionStatus status : TargetSelectionStatus.values()) {
            if (status == TargetSelectionStatus.HIT || status == TargetSelectionStatus.HIT_ID_UNKNOWN) {
                continue;
            }
            assertFalse(status.name(), status.isHit());
            assertFalse(status.name(), status.hasResolvedId());
        }
    }

    @Test
    public void missAndOutsideVideoAreNotFailures() {
        // 这两者是“正常但没有结果”，上层不该弹错误提示
        assertFalse(TargetSelectionStatus.MISS.isFailure());
        assertFalse(TargetSelectionStatus.OUTSIDE_VIDEO.isFailure());
        assertFalse(TargetSelectionStatus.HIT.isFailure());
        assertFalse(TargetSelectionStatus.HIT_ID_UNKNOWN.isFailure());
    }

    @Test
    public void captureAndGeometryProblemsAreFailures() {
        assertTrue(TargetSelectionStatus.CAPTURE_FAILED.isFailure());
        assertTrue(TargetSelectionStatus.NO_FRAME.isFailure());
        assertTrue(TargetSelectionStatus.GEOMETRY_UNAVAILABLE.isFailure());
        assertTrue(TargetSelectionStatus.CANCELLED.isFailure());
    }
}
