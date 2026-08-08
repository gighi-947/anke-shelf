package io.github.gighi947.ankeshelf.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 安科书架设计令牌（Design Tokens）。
 *
 * 规范（详见 docs/ANDROID_DESIGN_TOKENS.md）：
 * - 间距：只用 AnkeSpacing，禁止零散魔法值；
 * - 圆角：按组件角色复用 small/medium/large，胶囊（full）只给明确的小型操作/标签；
 * - 颜色：一律取 MaterialTheme.colorScheme / MaterialTheme.ankeColors，禁止硬编码临时色
 *   （NGA 显式彩色字与一次性图表细节除外）。
 */

/** 语义化间距令牌（4dp 基准）。 */
object AnkeSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** 语义化圆角令牌。 */
object AnkeRadius {
    /** 8dp：按钮、列表行、命中行、封面、小控件。 */
    val small: CornerBasedShape = RoundedCornerShape(8.dp)

    /** 12dp：卡片、面板、输入框、下拉框（对齐桌面 10~12px）。 */
    val medium: CornerBasedShape = RoundedCornerShape(12.dp)

    /** 16dp：对话框、底部弹层、浮层。 */
    val large: CornerBasedShape = RoundedCornerShape(16.dp)

    /** 全圆角：只用于明确的小型操作/标签（FilterChip、历史 chip、徽标、色点）。 */
    val pill: CornerBasedShape = RoundedCornerShape(percent = 50)
}

/** 与 MaterialTheme.shapes 绑定的形状令牌。 */
val AnkeShapes = Shapes(
    extraSmall = AnkeRadius.small,
    small = AnkeRadius.small,
    medium = AnkeRadius.medium,
    large = AnkeRadius.large,
    extraLarge = AnkeRadius.large,
)

/**
 * 颜色语义映射（对照桌面 PALETTES 的 bg/text/primary/accent + M3 error）：
 * - background/surface ← bg；text ← onSurface/onBackground；
 * - primary ← primary；accent ← secondary（桌面 accent 映射到 M3 secondary）；
 * - error ← M3 error；outline/surfaceVariant 等派生角色保持 M3 语义。
 * 组件引用本扩展或 colorScheme 均可，禁止写死色值。
 */
data class AnkeColorTokens(
    val background: Color,
    val surface: Color,
    val text: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val onError: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)

val MaterialTheme.ankeColors: AnkeColorTokens
    @Composable
    @ReadOnlyComposable
    get() = AnkeColorTokens(
        background = colorScheme.background,
        surface = colorScheme.surface,
        text = colorScheme.onSurface,
        primary = colorScheme.primary,
        onPrimary = colorScheme.onPrimary,
        accent = colorScheme.secondary,
        onAccent = colorScheme.onSecondary,
        error = colorScheme.error,
        onError = colorScheme.onError,
        outline = colorScheme.outline,
        outlineVariant = colorScheme.outlineVariant,
        surfaceVariant = colorScheme.surfaceVariant,
        onSurfaceVariant = colorScheme.onSurfaceVariant,
        primaryContainer = colorScheme.primaryContainer,
        onPrimaryContainer = colorScheme.onPrimaryContainer,
        secondaryContainer = colorScheme.secondaryContainer,
        onSecondaryContainer = colorScheme.onSecondaryContainer,
        surfaceContainerLow = colorScheme.surfaceContainerLow,
        surfaceContainerHigh = colorScheme.surfaceContainerHigh,
        surfaceContainerHighest = colorScheme.surfaceContainerHighest,
    )
