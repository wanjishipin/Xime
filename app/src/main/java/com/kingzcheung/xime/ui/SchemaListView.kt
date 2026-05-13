package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.SchemaInfo

@Composable
fun SchemaListView(
    schemas: List<SchemaInfo>,
    currentSchemaId: String,
    isDarkTheme: Boolean,
    backgroundColor: Color,
    accentColor: Color,
    onSelectSchema: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemBgColor = if (isDarkTheme) Color(0xFF45474A) else Color.White
    val textColor = if (isDarkTheme) Color(0xFFE8EAED) else Color(0xFF202124)
    val subTextColor = if (isDarkTheme) Color(0xFF9AA0A6) else Color(0xFF5F6368)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (schemas.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有可用的输入方案",
                    color = subTextColor,
                    fontSize = 13.sp
                )
            }
        } else {
            val rows = schemas.chunked(4)
            rows.forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { schema ->
                        SchemaGridItem(
                            schema = schema,
                            isSelected = schema.schemaId == currentSchemaId,
                            bgColor = itemBgColor,
                            textColor = textColor,
                            accentColor = accentColor,
                            onSelect = { onSelectSchema(schema.schemaId) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size < 4) {
                        repeat(4 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (rowIndex < rows.size - 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SchemaGridItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    bgColor: Color,
    textColor: Color,
    accentColor: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onSelect() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Keyboard,
            contentDescription = schema.name,
            tint = if (isSelected) accentColor else textColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = schema.name,
            color = if (isSelected) accentColor else textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}