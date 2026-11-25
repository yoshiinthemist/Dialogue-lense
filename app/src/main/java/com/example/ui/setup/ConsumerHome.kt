package com.example.ui.setup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DialogueLensProject
import com.example.ui.theme.*

@Composable
internal fun ConsumerHome(tab:Int,onTab:(Int)->Unit,url:String,onUrl:(String)->Unit,valid:Boolean,language:String,onLanguage:(String)->Unit,languages:List<String>,projects:List<DialogueLensProject>,onWatch:()->Unit,onLocal:()->Unit,onDemo:()->Unit,onOpen:(DialogueLensProject)->Unit,onExport:(DialogueLensProject)->Unit,studio:@Composable ()->Unit) {
    var languageMenu by remember { mutableStateOf(false) }
    Scaffold(containerColor=CinemaObsidian,contentWindowInsets=WindowInsets.safeDrawing,bottomBar={
        NavigationBar(containerColor=CinemaSurface) {
            listOf("Watch","Library","Studio").forEachIndexed { index,label ->
                NavigationBarItem(selected=tab==index,onClick={onTab(index)},icon={Icon(when(index){0->Icons.Filled.PlayCircle;1->Icons.Filled.VideoLibrary;else->Icons.Filled.Tune},null)},label={Text(label)})
            }
        }
    }) { inset ->
        if(tab==2) Box(Modifier.fillMaxSize().padding(inset)){studio()}
        else Box(Modifier.fillMaxSize().padding(inset),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Filled.ChatBubble,null,tint=LensSky);Spacer(Modifier.width(8.dp));Text("DialogueLens UX",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if(tab==0) Box { TextButton(onClick={languageMenu=true}){Text(language);Icon(Icons.Filled.ArrowDropDown,null)};DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){languages.forEach{lang->DropdownMenuItem(text={Text(lang)},onClick={onLanguage(lang);languageMenu=false})}} }
                }
                Text(if(tab==0)"What are we watching?" else "Your library",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                if(tab==0) {
                    Text("Dialogue that follows the story.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value=url,onValueChange=onUrl,label={Text("Paste a video link")},leadingIcon={Icon(Icons.Filled.Link,null)},singleLine=true,isError=url.isNotBlank()&&!valid,supportingText={if(url.isNotBlank()&&!valid)Text("Enter a valid video link")},modifier=Modifier.fillMaxWidth().testTag("start_url_input"))
                    Button(onClick=onWatch,enabled=valid,modifier=Modifier.fillMaxWidth().height(56.dp).testTag("start_submit_url_button")){Icon(Icons.Filled.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Watch video")}
                    OutlinedButton(onClick=onLocal,modifier=Modifier.fillMaxWidth().height(52.dp)){Icon(Icons.Filled.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("Open local video")}
                    TextButton(onClick=onDemo){Text("Try the interactive demo")}
                    HorizontalDivider()
                }
                Text(if(tab==0)"Recently opened" else "Saved videos and projects",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                if(projects.isEmpty())Text("Your videos will appear here after you save or open a project.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                (if(tab==0)projects.take(4)else projects).forEach{project->
                    ElevatedCard(onClick={onOpen(project)},modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Icon(Icons.Filled.Movie,null,tint=LensSky,modifier=Modifier.size(32.dp))
                            Column(Modifier.weight(1f)){Text(project.title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium);Text("${project.targetLanguage} subtitles",style=MaterialTheme.typography.bodySmall)}
                            if(tab==1)IconButton(onClick={onExport(project)}){Icon(Icons.Filled.Download,"Export subtitles")}else Icon(Icons.Filled.PlayArrow,"Open video")
                        }
                    }
                }
                if(tab==0&&projects.size>4)TextButton(onClick={onTab(1)}){Text("See all videos")}
            }
        }
    }
}
