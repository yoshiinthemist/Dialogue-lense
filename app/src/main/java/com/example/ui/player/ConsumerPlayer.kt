package com.example.ui.player
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.CaptionDisplayMode
import com.example.data.model.VideoSourceKind
import com.example.domain.analysis.TimeWindowRange
import com.example.ui.state.DialogueLensUiState
import com.example.ui.state.ScoutCaptionState
import com.example.ui.state.AnalysisStatus
import com.example.ui.theme.*

internal data class ViewerReadiness(val label:String,val needsAttention:Boolean=false)
internal fun viewerReadiness(state:DialogueLensUiState):ViewerReadiness{
    val cap=state.scoutCaptionState
    val coverage=state.coverageSummary
    if(cap is ScoutCaptionState.Error && cap.isQuotaExhausted)return ViewerReadiness("AI usage limit reached",true)
    if(state.isScoutCatchUpBuffering)return ViewerReadiness("Preparing upcoming dialogue")
    if(coverage.isFullyVerified)return ViewerReadiness("All dialogue prepared")
    val here=state.currentTimeMs
    if(coverage.failedRanges.any{here>=it.startMs&&here<it.endMs})return ViewerReadiness("Some dialogue unavailable here",true)
    if(coverage.bufferedRanges.any{here>=it.startMs&&here<it.endMs})return ViewerReadiness("Ready to watch · preparing more")
    if(cap is ScoutCaptionState.Error||state.analysisStatus is AnalysisStatus.Error)return ViewerReadiness("Some dialogue unavailable",true)
    if(state.project.videoSource.kind==VideoSourceKind.DEMO_SCENARIO)return ViewerReadiness("Demo dialogue available")
    if(coverage.totalDurationMs>0L||state.isCinemaIntroActive||state.analysisStatus is AnalysisStatus.Processing||cap is ScoutCaptionState.FetchingCaptions||cap is ScoutCaptionState.AiTranscribing)return ViewerReadiness("Preparing dialogue")
    return ViewerReadiness("Dialogue status unknown")
}
@Composable
internal fun ConsumerPlayer(state:DialogueLensUiState,onSeek:(Long)->Unit,onPlay:()->Unit,onMute:()->Unit,onFullscreen:()->Unit,onSpeed:()->Unit,onMode:(CaptionDisplayMode)->Unit,onAppearance:()->Unit,onCharacters:()->Unit,onStudio:()->Unit,onAttach:()->Unit,onRetry:()->Unit,onContinue:()->Unit,onBrowser:()->Unit,onLanguage:()->Unit){
    val duration=state.project.videoSource.durationMs.coerceAtLeast(1L)
    val readiness=viewerReadiness(state)
    var captions by remember{mutableStateOf(false)}
    var more by remember{mutableStateOf(false)}
    var scrub by remember{mutableStateOf<Float?>(null)}
    val progress=scrub?:(state.currentTimeMs.toDouble()/duration).toFloat().coerceIn(0f,1f)
    Surface(color=CinemaSurface,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Canvas(Modifier.fillMaxWidth().height(4.dp).testTag("consumer_dialogue_coverage")){
                drawRect(Color.White.copy(alpha=.10f))
                fun paint(r:TimeWindowRange,c:Color){
                    val start=(r.startMs.toDouble()/duration).toFloat().coerceIn(0f,1f)*size.width
                    val end=(r.endMs.toDouble()/duration).toFloat().coerceIn(0f,1f)*size.width
                    if(end>start)drawRect(c,Offset(start,0f),Size(end-start,size.height))
                }
                state.coverageSummary.bufferedRanges.forEach{paint(it,LensMint.copy(alpha=.65f))}
                state.coverageSummary.failedRanges.forEach{paint(it,LensAmber.copy(alpha=.8f))}
            }
            Slider(value=progress,onValueChange={scrub=it},onValueChangeFinished={scrub?.let{onSeek((it*duration).toLong())};scrub=null},modifier=Modifier.fillMaxWidth().testTag("timeline_seek_slider"))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(viewerTime((progress*duration).toLong()),style=MaterialTheme.typography.labelMedium);Text(viewerTime(duration),style=MaterialTheme.typography.labelMedium)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly){
                IconButton(onClick={onSeek((state.currentTimeMs-10000).coerceAtLeast(0))}){Icon(Icons.Filled.Replay10,"Back ten seconds")}
                FilledIconButton(onClick=onPlay,modifier=Modifier.size(56.dp)){Icon(if(state.isPlaying)Icons.Filled.Pause else Icons.Filled.PlayArrow,if(state.isPlaying)"Pause" else "Play")}
                IconButton(onClick={onSeek((state.currentTimeMs+10000).coerceAtMost(duration))}){Icon(Icons.Filled.Forward10,"Forward ten seconds")}
                IconButton(onClick=onMute){Icon(if(state.isMuted)Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,if(state.isMuted)"Unmute" else "Mute")}
                IconButton(onClick=onFullscreen){Icon(Icons.Filled.Fullscreen,"Fullscreen")}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Box{
                    TextButton(onClick={captions=true}){Icon(Icons.Filled.Subtitles,null);Spacer(Modifier.width(6.dp));Text(when(state.displayMode){CaptionDisplayMode.OFF->"Subtitles off";CaptionDisplayMode.CLASSIC->"Subtitles";else->"Speech bubbles"})}
                    DropdownMenu(expanded=captions,onDismissRequest={captions=false}){
                        listOf(CaptionDisplayMode.BALLOONS to "Speech bubbles",CaptionDisplayMode.CLASSIC to "Traditional subtitles",CaptionDisplayMode.OFF to "Subtitles off").forEach{(mode,label)->DropdownMenuItem(text={Text(label)},onClick={onMode(mode);captions=false})}
                        DropdownMenuItem(text={Text("Appearance")},onClick={captions=false;onAppearance()})
                        DropdownMenuItem(text={Text("Subtitle language · ${state.project.targetLanguage}")},onClick={captions=false;onLanguage()})
                        DropdownMenuItem(text={Text("Attach subtitle file")},onClick={captions=false;onAttach()})
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onCharacters){Text("Characters")}
                Box{
                    IconButton(onClick={more=true}){Icon(Icons.Filled.MoreVert,"More options")}
                    DropdownMenu(expanded=more,onDismissRequest={more=false}){
                        DropdownMenuItem(text={Text("Playback speed · ${state.playbackSpeed}×")},onClick={more=false;onSpeed()})
                        DropdownMenuItem(text={Text("Studio · edit and export")},onClick={more=false;onStudio()})
                        if(state.project.videoSource.kind==VideoSourceKind.WEB_STREAM)DropdownMenuItem(text={Text("Browser controls")},onClick={more=false;onBrowser()})
                    }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Icon(if(readiness.needsAttention)Icons.Filled.WarningAmber else Icons.Filled.Info,null,tint=if(readiness.needsAttention)LensAmber else LensMint,modifier=Modifier.size(16.dp))
                Text(readiness.label,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                val cap=state.scoutCaptionState
                if(readiness.needsAttention&&cap is ScoutCaptionState.Error&&state.project.videoSource.kind==VideoSourceKind.YOUTUBE_COMPLIANT&&!cap.isQuotaExhausted)TextButton(onClick=onRetry){Text("Retry")}
                if(state.isScoutCatchUpBuffering)TextButton(onClick=onContinue){Text("Watch now")}
            }
        }
    }
}
private fun viewerTime(ms:Long):String{val s=ms.coerceAtLeast(0)/1000;return if(s>=3600)"%d:%02d:%02d".format(s/3600,s/60%60,s%60)else "%d:%02d".format(s/60,s%60)}
