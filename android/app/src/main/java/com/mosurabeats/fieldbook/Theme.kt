package com.mosurabeats.fieldbook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun hex(h: String, a: Float = 1f): Color {
    val v = h.removePrefix("#").toLong(16)
    return Color(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt(), (a * 255).toInt())
}

data class Palette(
    val id: String, val dark: Boolean,
    val bg: Color, val p1: Color, val p2: Color, val p3: Color, val line: Color, val tx: Color, val tx2: Color, val mu: Color, val dim: Color,
    val acc: Color, val acc2: Color, val acctx: Color, val pos: Color, val neg: Color,
    val hi: Color, val hitx: Color, val hi2: Color,
    val hdr: Color, val hdrtx: Color, val hdrmu: Color,
    val rlo: Color, val rloBg: Color, val rmid: Color, val rmidBg: Color, val rok: Color, val rokBg: Color, val rhi: Color, val rhiBg: Color,
    val aiBg: Color, val aiLine: Color, val aiTx: Color,
) {
    fun ratingColors(r: String): Pair<Color, Color> {
        val n = r.toIntOrNull() ?: return dim to p2
        return when { n <= 2 -> rlo to rloBg; n == 3 -> rmid to rmidBg; n <= 5 -> rok to rokBg; else -> rhi to rhiBg }
    }

    companion object {
        /** 11K Deck — Dr. Sample top-deck black, silver text, strip red actions, pad blue/pink accents. */
        val deck = Palette("k11", true,
            hex("1F2023"), hex("26282C"), hex("2C2E32"), hex("36383D"), hex("3A3D42"), hex("DEDFE1"), hex("C9CBCF"), hex("A3A6AC"), hex("80838A"),
            hex("9FBCD6"), hex("B9CFE3"), hex("16181B"), hex("9FBCD6"), hex("FF6B62"),
            hex("D8262E"), Color.White, hex("EFB3B5"),
            hex("17181B"), hex("DEDFE1"), hex("A3A6AC"),
            hex("FF9A93"), hex("D8262E", .30f), hex("F2C18D"), hex("F0A050", .18f), hex("DEDFE1"), hex("36383D"), hex("C3D7EA"), hex("9FBCD6", .24f),
            hex("2C3A47"), hex("4A6378"), hex("DCE8F3"))

        /** 11K Silver — the silver body with the black deck header. */
        val silver = Palette("k11s", false,
            hex("D6D7DA"), hex("ECECEE"), hex("E1E2E5"), hex("CFD1D5"), hex("BEC0C5"), hex("1F2023"), hex("2C2E32"), hex("4F5258"), hex("676A71"),
            hex("3F6F99"), hex("3F6F99"), Color.White, hex("2F5F8A"), hex("B81F27"),
            hex("D8262E"), Color.White, hex("B81F27"),
            hex("1F2023"), hex("DEDFE1"), hex("A3A6AC"),
            hex("B81F27"), hex("D8262E", .13f), hex("8A5300"), hex("C87800", .14f), hex("1F2023"), hex("DCDDE0"), hex("2F5F8A"), hex("3F6F99", .15f),
            hex("E2EAF2"), hex("A9BFD3"), hex("2C5378"))

        /** Slate — the 26K / SP-12 look. */
        val slate = Palette("slate", true,
            hex("4E5C6A"), hex("3C4753"), hex("2F3943"), hex("46525F"), hex("5B6876"), hex("E7E7E1"), hex("D7DCE1"), hex("CDD5DC"), hex("A7B1BC"),
            hex("39B4F2"), hex("8FD6F8"), hex("0E1D28"), hex("8EE3A8"), hex("FF9EA2"),
            hex("D42A31"), Color.White, hex("8FD6F8"),
            hex("2F3943"), hex("E7E7E1"), hex("C3CCD4"),
            hex("FFB4B7"), hex("E8343B", .32f), hex("FFD08A"), hex("FFAA3C", .22f), hex("E7E7E1"), hex("4A5664"), hex("A9F0BF"), hex("50C878", .24f),
            hex("33658C"), hex("4C82AD"), hex("EAF6FF"))

        fun named(mode: String, systemDark: Boolean) = when (mode) {
            "k11s" -> silver; "slate" -> slate; "auto" -> if (systemDark) deck else silver; else -> deck
        }
    }
}

val LocalPal = staticCompositionLocalOf { Palette.deck }
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

val Archivo = FontFamily(Font(R.font.archivo_black))
val Mono = FontFamily.Monospace

@Composable fun pal() = LocalPal.current

// ---------- Building blocks ----------

@Composable
fun Card(title: String? = null, modifier: Modifier = Modifier, badge: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = pal()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.p1).border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = p.mu)
            badge?.invoke()
        }
        content()
    }
}

enum class TagStyle { Accent, Danger, Info, Quiet }

@Composable
fun Tag(text: String, style: TagStyle = TagStyle.Accent) {
    val p = pal()
    val (fg, bg) = when (style) {
        TagStyle.Accent -> p.hitx to p.hi; TagStyle.Danger -> Color.White to p.neg
        TagStyle.Info -> p.acctx to p.acc; TagStyle.Quiet -> p.acc2 to p.p3
    }
    Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.5.dp))
}

@Composable
fun RatingPill(r: String, modifier: Modifier = Modifier) {
    val (fg, bg) = pal().ratingColors(r)
    Box(modifier.defaultMinSize(40.dp, 32.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(r, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (Rating.isNum(r)) 15.sp else 12.sp, color = fg)
    }
}

@Composable
fun Chip(text: String, on: Boolean = false, onClick: () -> Unit) {
    val p = pal()
    Text(text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
        color = if (on) p.hi2 else p.mu,
        modifier = Modifier.clip(CircleShape).background(if (on) p.hi.copy(alpha = .12f) else p.p2)
            .border(1.dp, if (on) p.hi else p.line, CircleShape).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp))
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = pal()
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.hi.copy(alpha = if (enabled) 1f else .45f))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, color = p.hitx, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = pal()
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.p3).border(1.dp, p.line, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (danger) p.neg else p.tx.copy(alpha = if (enabled) 1f else .45f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun AIButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.aiBg).border(1.dp, p.aiLine, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled && !busy, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), color = p.aiTx, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = p.aiTx.copy(alpha = if (enabled) 1f else .5f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Single- or multi-line text box in the FieldBook style. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    number: Boolean = false, decimal: Boolean = false, singleLine: Boolean = true, minHeight: Dp = 0.dp,
    enabled: Boolean = true, mono: Boolean = false, center: Boolean = false, caps: Boolean = false, password: Boolean = false,
) {
    val p = pal()
    BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = singleLine,
        textStyle = TextStyle(color = p.tx, fontSize = 15.5.sp, fontFamily = if (mono) Mono else null, textAlign = if (center) TextAlign.Center else TextAlign.Start),
        cursorBrush = SolidColor(p.hi),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { password -> KeyboardType.Password; decimal -> KeyboardType.Decimal; number -> KeyboardType.Number; else -> KeyboardType.Text },
            capitalization = when { caps -> KeyboardCapitalization.Characters; number || decimal || password -> KeyboardCapitalization.None; else -> KeyboardCapitalization.Sentences },
            autoCorrectEnabled = !password),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.p2)
            .border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (center) Alignment.Center else Alignment.TopStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = p.dim, fontSize = 15.sp)
                inner()
            }
        },
    )
}

@Composable
fun NoteEditor(value: String, onChange: (String) -> Unit, placeholder: String = "", minHeight: Dp = 96.dp, enabled: Boolean = true) =
    Field(value, onChange, placeholder = placeholder, singleLine = false, minHeight = minHeight, enabled = enabled)

@Composable
fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal().mu)
        content()
    }
}

@Composable
fun InfoBox(title: String, lines: List<String>, danger: Boolean = true) {
    val p = pal()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (danger) p.neg.copy(alpha = .12f) else p.p2)
        .border(1.dp, if (danger) p.neg else p.line, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (danger) p.tx else p.mu)
        lines.forEach { Text("• $it", fontSize = 13.5.sp, color = if (danger) p.tx else p.mu) }
    }
}

@Composable
fun Hint(text: String) = Text(text, fontSize = 12.5.sp, color = pal().mu)

@Composable
fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = p.mu, fontSize = 12.5.sp)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = p.hi, uncheckedThumbColor = p.mu, uncheckedTrackColor = p.p3, uncheckedBorderColor = p.line))
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val p = pal()
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEach { (v, l) ->
            val on = v == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) p.p3 else Color.Transparent)
                .clickable { onSelect(v) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(l, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) p.tx else p.mu, maxLines = 1)
            }
        }
    }
}

/** A tappable field that opens a menu of choices. */
@Composable
fun <T> MenuField(label: String, items: List<Pair<T, String>>, placeholder: Boolean = false, enabled: Boolean = true, onPick: (T) -> Unit) {
    val p = pal()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (placeholder) p.dim else p.tx, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = p.mu)
        }
        DropdownMenu(open, { open = false }, Modifier.background(p.p1).heightIn(max = 420.dp)) {
            items.forEach { (v, l) -> DropdownMenuItem(text = { Text(l, color = p.tx) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

/** Date chip that opens the system date picker. Dates are yyyy-MM-dd strings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    val p = pal()
    var open by remember { mutableStateOf(false) }
    Text(DateUtil.parse(date)?.let { DateUtil.us(date) } ?: "Pick a date", color = p.tx, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp, vertical = 11.dp))
    if (open) {
        val initial = (DateUtil.parse(date) ?: java.time.LocalDate.now()).toEpochDay() * 86_400_000L
        val st = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onChange(DateUtil.string(java.time.LocalDate.ofEpochDay(it / 86_400_000L))) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } }) { DatePicker(st) }
    }
}

@Composable
fun Disclosure(title: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = pal()
    var open by remember { mutableStateOf(startOpen) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.mu, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = p.mu)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
    }
}

@Composable
fun Confirm(text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = pal()
    AlertDialog(onDismissRequest = onDismiss, containerColor = p.p1,
        text = { Text(text, color = p.tx, fontSize = 15.sp) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(action, color = p.neg, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.mu) } })
}

@Composable
fun GroupHeader(text: String) = Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = pal().hi2)

/** The 11K-style app logo: silver FTO, LED step meter, four-colour stripe. */
@Composable
fun AppLogo(size: Dp = 36.dp) {
    val s = size.value
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f)).background(hex("1F2023"))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("FTO", fontFamily = Archivo, fontSize = (s * 0.31f).sp, color = hex("DEDFE1"), lineHeight = (s * 0.34f).sp)
            Row(Modifier.padding(top = (s * 0.05f).dp), horizontalArrangement = Arrangement.spacedBy((s * 0.02f).dp)) {
                repeat(11) { k -> Box(Modifier.size((s * 0.046f).dp, (s * 0.07f).dp).clip(RoundedCornerShape((s * 0.01f).dp)).background(hex(if (k < 6) "FF3B2F" else "36383D"))) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height((s * 0.12f).dp)) { listOf("D8262E", "EFB3B5", "DEDFE1", "9FBCD6").forEach { Box(Modifier.weight(1f).fillMaxHeight().background(hex(it))) } }
        }
    }
}

@Composable
fun SheetTitle(title: String, onClose: () -> Unit, action: String? = null, onAction: (() -> Unit)? = null) {
    val p = pal()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClose) { Text("Close", color = p.mu) }
        Text(title, color = p.tx, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) TextButton(onAction) { Text(action, color = p.hi2, fontWeight = FontWeight.Bold) } else Spacer(Modifier.width(64.dp))
    }
}


/** A field-styled row that opens a picker. */
@Composable
fun PickRow(label: String, placeholder: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = pal()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.p2).border(1.dp, p.line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (placeholder) p.dim else p.tx, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.KeyboardArrowDown, null, tint = p.mu)
    }
}
