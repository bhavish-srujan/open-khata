package com.example.khatabook.ui.components

import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.khatabook.ui.theme.KhatabookHeaderBlue

/**
 * The app's own colours for a Material date picker.
 *
 * Every picker in the app used the stock Material palette, which renders the
 * selection and today-marker in purple — the one control on any given screen
 * not in the app's blue. Defined once here so the three pickers (transaction
 * date, Scan Khata sheet date, report range) cannot drift apart.
 *
 * Only the accent colours are overridden; container and text colours stay on
 * the Material defaults so the picker keeps working in both light and dark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun khatabookDatePickerColors(): DatePickerColors = DatePickerDefaults.colors(
    selectedDayContainerColor = KhatabookHeaderBlue,
    selectedDayContentColor = Color.White,
    todayContentColor = KhatabookHeaderBlue,
    todayDateBorderColor = KhatabookHeaderBlue,
    currentYearContentColor = KhatabookHeaderBlue,
    selectedYearContainerColor = KhatabookHeaderBlue,
    selectedYearContentColor = Color.White,
)
