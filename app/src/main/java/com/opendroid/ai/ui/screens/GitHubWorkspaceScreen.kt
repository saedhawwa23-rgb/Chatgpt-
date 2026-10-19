package com.opendroid.ai.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opendroid.ai.data.repository.GitHubRepoSummary
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.GitHubWorkspaceViewModel

@Composable
fun GitHubWorkspaceScreen(viewModel: GitHubWorkspaceViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val authenticated by viewModel.authenticated.collectAsState()
    val account by viewModel.account.collectAsState()
    val repos by viewModel.repositories.collectAsState()
    val files by viewModel.files.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val issues by viewModel.issues.collectAsState()
    val deviceCode by viewModel.deviceCode.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    var selectedRepo by remember { mutableStateOf<GitHubRepoSummary?>(null) }
    var path by remember { mutableStateOf("") }
    var issueTitle by remember { mutableStateOf("") }
    var issueBody by remember { mutableStateOf("") }
    LaunchedEffect(authenticated) { if (authenticated && repos.isEmpty()) viewModel.refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("GITHUB WORKSPACE", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }, containerColor = AppTheme.colors.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (!viewModel.configured) Text("GitHub OAuth is not configured in this build. Set GITHUB_CLIENT_ID at build time; no client secret belongs in the APK.", color = AppTheme.colors.accentRed)
                else if (!authenticated) {
                    Text("Authenticate with GitHub Device Authorization. The app never asks for or stores your GitHub password.", color = AppTheme.colors.textSecondary)
                    Button(onClick = viewModel::beginLogin, enabled = !busy) { Text("START GITHUB LOGIN") }
                    deviceCode?.let { code ->
                        Text("Code: ${code.userCode}", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, code.verificationUri.toUri())) }) { Text("OPEN ${code.verificationUri}") }
                        Button(onClick = viewModel::pollLogin, enabled = !busy) { Text("I AUTHORIZED — CHECK") }
                    }
                } else {
                    Text("Authenticated as ${account?.login ?: "GitHub user"}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = viewModel::refresh, enabled = !busy) { Text("REFRESH") }; OutlinedButton(onClick = viewModel::signOut) { Text("SIGN OUT") } }
                }
                message?.let { Text(it, color = AppTheme.colors.textSecondary) }
            }
            if (authenticated) {
                item { Text("YOUR REPOSITORIES", fontWeight = FontWeight.Bold) }
                items(repos, key = { it.id }) { repo ->
                    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(repo.fullName, fontWeight = FontWeight.Bold)
                            Text(if (repo.private) "Private · ${repo.defaultBranch}" else "Public · ${repo.defaultBranch}", color = AppTheme.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { selectedRepo = repo; path = ""; viewModel.openRepository(repo) }) { Text("FILES") }
                                OutlinedButton(onClick = { selectedRepo = repo; viewModel.loadIssues(repo) }) { Text("ISSUES") }
                            }
                        }
                    }
                }
                selectedRepo?.let { repo ->
                    item {
                        Text("${repo.fullName} FILES", fontWeight = FontWeight.Bold)
                        OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Path (blank for root)") })
                        Button(onClick = { viewModel.openRepository(repo, path) }, enabled = !busy) { Text("LOAD PATH") }
                    }
                    items(files, key = { it.path }) { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (file.type == "dir") "${file.path}/" else file.path, Modifier.weight(1f))
                            if (file.type == "file") OutlinedButton(onClick = { viewModel.inspect(repo, file) }) { Text("INSPECT") }
                        }
                    }
                    selectedFile?.let { file -> item { Text("${file.path}\n\n${file.content.orEmpty()}", color = AppTheme.colors.textSecondary) } }
                    item {
                        Text("CREATE ISSUE", fontWeight = FontWeight.Bold)
                        OutlinedTextField(issueTitle, { issueTitle = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
                        OutlinedTextField(issueBody, { issueBody = it }, Modifier.fillMaxWidth(), label = { Text("Body") })
                        Button(onClick = { viewModel.createIssue(repo, issueTitle, issueBody) }, enabled = issueTitle.isNotBlank() && !busy) { Text("CREATE ISSUE") }
                        issues.forEach { issue -> Text("#${issue.number} ${issue.title} · ${issue.state}") }
                    }
                }
            }
        }
    }
}
