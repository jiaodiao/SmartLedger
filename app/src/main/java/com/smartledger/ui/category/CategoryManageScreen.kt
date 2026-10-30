package com.smartledger.ui.category

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.SmartLedgerApp
import com.smartledger.data.db.entity.Category
import com.smartledger.ui.components.SmartLedgerDialog
import com.smartledger.ui.theme.SmartLedgerColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════

class CategoryManageViewModel(application: Application) : AndroidViewModel(application) {
    private val categoryRepo = (application as SmartLedgerApp).categoryRepository

    val expenseCategories: Flow<List<Category>> = categoryRepo.getByType("expense")
    val incomeCategories: Flow<List<Category>> = categoryRepo.getByType("income")

    fun insertCategory(name: String, type: String, color: Long) {
        viewModelScope.launch {
            categoryRepo.insert(
                Category(name = name, type = type, color = color, icon = "default")
            )
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch {
            categoryRepo.update(category)
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            categoryRepo.delete(category)
        }
    }
}

// ═══════════════════════════════════════════════════════
// Screen
// ═══════════════════════════════════════════════════════

@Composable
fun CategoryManageScreen(
    onBack: () -> Unit = {},
    viewModel: CategoryManageViewModel = viewModel()
) {
    var selectedType by remember { mutableStateOf("expense") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    val expenseCategories by viewModel.expenseCategories.collectAsState(initial = emptyList())
    val incomeCategories by viewModel.incomeCategories.collectAsState(initial = emptyList())

    val currentCategories = if (selectedType == "expense") expenseCategories else incomeCategories

    Box(modifier = Modifier.fillMaxSize().background(SmartLedgerColors.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ═══ 顶部栏 ═══
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = SmartLedgerColors.fg)
                    }
                    Text(
                        text = "分类管理",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = SmartLedgerColors.fg
                    )
                }
            }

            // ═══ 类型切换 ═══
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TypeTab("支出", selectedType == "expense") { selectedType = "expense" }
                    Spacer(modifier = Modifier.width(48.dp))
                    TypeTab("收入", selectedType == "income") { selectedType = "income" }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ═══ 分类列表 ═══
            items(currentCategories) { category ->
                CategoryItem(
                    category = category,
                    onEdit = { editingCategory = category },
                    onDelete = { viewModel.deleteCategory(category) }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            // ═══ 添加按钮 ═══
            item {
                TextButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加分类")
                }
            }
        }
    }

    // ═══ 添加分类弹窗 ═══
    if (showAddDialog) {
        CategoryEditorDialog(
            title = "添加${if (selectedType == "expense") "支出" else "收入"}分类",
            confirmText = "添加",
            onSave = { name, color ->
                viewModel.insertCategory(name, selectedType, color)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ═══ 编辑分类弹窗 ═══
    if (editingCategory != null) {
        CategoryEditorDialog(
            title = "编辑分类",
            confirmText = "保存",
            initialName = editingCategory!!.name,
            initialColor = editingCategory!!.color,
            onSave = { name, color ->
                viewModel.updateCategory(editingCategory!!.copy(name = name, color = color))
                editingCategory = null
            },
            onDismiss = { editingCategory = null }
        )
    }
}

@Composable
private fun TypeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) SmartLedgerColors.fg else SmartLedgerColors.fgSecondary
        )
        if (selected) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(2.dp)
                    .background(SmartLedgerColors.fg, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun CategoryItem(
    category: Category,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(category.color).copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                getCategoryIcon(category.name),
                contentDescription = category.name,
                tint = Color(category.color),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            color = SmartLedgerColors.fg,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = SmartLedgerColors.fgSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = SmartLedgerColors.fgSecondary, modifier = Modifier.size(20.dp))
        }
    }

    if (showDeleteConfirm) {
        SmartLedgerDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = "删除分类",
            text = "确定要删除「${category.name}」吗？",
            confirmText = "删除",
            confirmColor = SmartLedgerColors.expense,
            onConfirm = { onDelete(); showDeleteConfirm = false },
            dismissText = "取消"
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    title: String,
    confirmText: String,
    initialName: String = "",
    initialColor: Long = categoryColors.first().second,
    onSave: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    SmartLedgerDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = confirmText,
        onConfirm = {
            if (name.isNotBlank()) onSave(name.trim(), selectedColor)
        },
        dismissText = "取消",
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SmartLedgerColors.accent,
                        focusedLabelColor = SmartLedgerColors.accent,
                        cursorColor = SmartLedgerColors.accent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择颜色", style = MaterialTheme.typography.labelMedium, color = SmartLedgerColors.fgSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(Modifier.size(16.dp).background(Color(selectedColor), RoundedCornerShape(4.dp)))
                }
                Spacer(modifier = Modifier.height(8.dp))
                categoryColors.chunked(6).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, color) ->
                            val selected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(color), RoundedCornerShape(10.dp))
                                    .semantics { contentDescription = label }
                                    .selectable(selected = selected, role = Role.RadioButton) { selectedColor = color },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    )
}

private val categoryColors = listOf(
    "红色" to 0xFFD94848, "蓝色" to 0xFF3E63DD, "绿色" to 0xFF2D9D63,
    "紫色" to 0xFF9C27B0, "橙色" to 0xFFFF7043, "棕色" to 0xFF795548,
    "玫红" to 0xFFC2185B, "靛蓝" to 0xFF5C6BC0, "青色" to 0xFF00838F,
    "琥珀" to 0xFFB77900, "草绿" to 0xFF6B8E23, "灰蓝" to 0xFF546E7A,
    "珊瑚" to 0xFFE57373, "天蓝" to 0xFF0288D1, "蓝绿" to 0xFF00897B,
    "深紫" to 0xFF673AB7, "赭色" to 0xFFB45309, "灰色" to 0xFF757575
)

private fun getCategoryIcon(name: String): ImageVector {
    return when (name) {
        "餐饮" -> Icons.Outlined.Restaurant
        "交通" -> Icons.Outlined.DirectionsBus
        "购物" -> Icons.Outlined.ShoppingBag
        "娱乐" -> Icons.Outlined.OndemandVideo
        "居住" -> Icons.Outlined.Home
        "医疗" -> Icons.Outlined.FavoriteBorder
        "教育" -> Icons.Outlined.MenuBook
        "通讯" -> Icons.Outlined.Phone
        "日用" -> Icons.Outlined.ShoppingCart
        "工资" -> Icons.Outlined.AttachMoney
        "理财" -> Icons.Outlined.TrendingUp
        "红包" -> Icons.Outlined.CardGiftcard
        "转账" -> Icons.Outlined.SwapHoriz
        else -> Icons.Outlined.MoreHoriz
    }
}
