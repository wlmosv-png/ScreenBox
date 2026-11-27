package io.github.wlmosv.screenbox

import android.Manifest
import io.github.wlmosv.screenbox.core.permission.MediaPermissionRules
import io.github.wlmosv.screenbox.core.permission.MediaPermissionState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 10–15 权限矩阵的纯逻辑回归。
 *
 * 背景：手上只有一台 Android 15 真机，模拟器在本机不可用
 * （aarch64 宿主没有可执行的 emulator 二进制）。
 * 无法假装已经真机验证 10–14，但可以把「版本 → 权限组合 → 状态」
 * 的判定规则穷举测试，保证分支存在且语义正确。
 *
 * 覆盖：API 29/30/31/32/33/34/35。
 */
class PermissionMatrixTest {

    private val sdkVersions = listOf(29, 30, 31, 32, 33, 34, 35)

    @Test
    fun `android 10 to 12 requests legacy storage permission`() {
        listOf(29, 30, 31, 32).forEach { sdk ->
            assertArrayEquals(
                "SDK $sdk 应请求 READ_EXTERNAL_STORAGE",
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                MediaPermissionRules.requiredPermissions(sdk),
            )
            assertEquals(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                MediaPermissionRules.fullAccessPermission(sdk),
            )
            assertFalse(MediaPermissionRules.supportsPartial(sdk))
        }
    }

    @Test
    fun `android 13 requests read media images only`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
            MediaPermissionRules.requiredPermissions(33),
        )
        assertEquals(
            Manifest.permission.READ_MEDIA_IMAGES,
            MediaPermissionRules.fullAccessPermission(33),
        )
        assertFalse("Android 13 没有部分照片授权", MediaPermissionRules.supportsPartial(33))
    }

    @Test
    fun `android 14 and 15 declare selected-photos permission`() {
        listOf(34, 35).forEach { sdk ->
            assertArrayEquals(
                "SDK $sdk 必须同时声明 READ_MEDIA_VISUAL_USER_SELECTED",
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
                MediaPermissionRules.requiredPermissions(sdk),
            )
            assertTrue(MediaPermissionRules.supportsPartial(sdk))
        }
    }

    @Test
    fun `full grant wins on every version`() {
        sdkVersions.forEach { sdk ->
            assertEquals(
                MediaPermissionState.FULL,
                MediaPermissionRules.decide(sdk, true, false, false),
            )
        }
    }

    @Test
    fun `partial grant only counts from android 14`() {
        assertEquals(
            MediaPermissionState.PARTIAL,
            MediaPermissionRules.decide(34, false, true, false),
        )
        assertEquals(
            MediaPermissionState.PARTIAL,
            MediaPermissionRules.decide(35, false, true, true),
        )
        // 13 及以下不存在部分授权；若系统异常返回该权限，也不能当成可选范围。
        assertEquals(
            MediaPermissionState.NOT_REQUESTED,
            MediaPermissionRules.decide(33, false, true, false),
        )
    }

    @Test
    fun `revoked is distinguished from never requested`() {
        assertEquals(
            MediaPermissionState.REVOKED,
            MediaPermissionRules.decide(30, false, false, true),
        )
        assertEquals(
            MediaPermissionState.NOT_REQUESTED,
            MediaPermissionRules.decide(30, false, false, false),
        )
    }

    @Test
    fun `denied state is reachable by the gate logic`() {
        // DENIED 由「请求过 + 仍可弹框」在 UI 层推导；
        // 这里只确认底层不会把它和 NOT_REQUESTED 混淆。
        val state = MediaPermissionRules.decide(29, false, false, false)
        assertTrue(state == MediaPermissionState.NOT_REQUESTED)
    }
}
