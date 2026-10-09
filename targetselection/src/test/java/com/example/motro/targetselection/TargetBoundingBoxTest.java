package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.geometry.TargetBoundingBox;

import org.junit.Test;

/** 目标框：顶点顺序归一化、命中测试、边界行为。 */
public class TargetBoundingBoxTest {

    private static final float EPS = 0.0001f;

    @Test
    public void reversedVerticesAreNormalized() {
        // 不同 provider 给顶点顺序可能不同，统一在构造处收敛
        TargetBoundingBox box = TargetBoundingBox.of(30f, 40f, 10f, 20f);
        assertEquals(10f, box.left, EPS);
        assertEquals(20f, box.top, EPS);
        assertEquals(30f, box.right, EPS);
        assertEquals(40f, box.bottom, EPS);
        assertEquals(20f, box.width(), EPS);
        assertEquals(20f, box.height(), EPS);
        assertEquals(20f, box.centerX(), EPS);
        assertEquals(30f, box.centerY(), EPS);
    }

    @Test
    public void ofSizeExpandsFromTopLeft() {
        TargetBoundingBox box = TargetBoundingBox.ofSize(5f, 6f, 10f, 20f);
        assertEquals(5f, box.left, EPS);
        assertEquals(6f, box.top, EPS);
        assertEquals(15f, box.right, EPS);
        assertEquals(26f, box.bottom, EPS);
    }

    @Test
    public void containsIncludesBoundary() {
        TargetBoundingBox box = TargetBoundingBox.of(10f, 10f, 20f, 20f);
        assertTrue(box.contains(15f, 15f));
        assertTrue(box.contains(10f, 10f));
        assertTrue(box.contains(20f, 20f));
        assertFalse(box.contains(9.9f, 15f));
        assertFalse(box.contains(15f, 20.1f));
    }

    @Test
    public void degenerateBoxIsEmpty() {
        assertTrue(TargetBoundingBox.of(5f, 5f, 5f, 5f).isEmpty());
        assertTrue(TargetBoundingBox.ofSize(5f, 5f, 0f, 10f).isEmpty());
        assertFalse(TargetBoundingBox.ofSize(5f, 5f, 1f, 1f).isEmpty());
    }

    @Test
    public void nonFiniteVerticesAreRejected() {
        float[] bad = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float value : bad) {
            try {
                TargetBoundingBox.of(value, 0f, 1f, 1f);
                fail("expected IllegalArgumentException for " + value);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("left"));
            }
        }
        try {
            TargetBoundingBox.ofSize(0f, 0f, -1f, 1f);
            fail("expected IllegalArgumentException for negative width");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("width"));
        }
    }

    @Test
    public void negativeCoordinatesArePreservedNotClamped() {
        // 越界值原样保留：裁剪策略属于上层，模块不擅自改动 provider 的输出
        TargetBoundingBox box = TargetBoundingBox.of(-10f, -10f, 10f, 10f);
        assertEquals(-10f, box.left, EPS);
        assertEquals(20f, box.width(), EPS);
    }
}
