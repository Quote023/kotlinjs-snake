import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.KeyboardEvent
import org.w3c.fetch.RequestInit
import kotlin.math.max



/**
 * Ponto de entrada do software, inicializa o jogo no momento que a página é carregada por completo
 */
@Suppress("UNCHECKED_CAST")
suspend fun main() {
   val response = window.fetch("https://jsonbin.org/quote023/leaderboard").await()
   val ranking = if(response.ok) {
     response.json().await().let{
       if(it is Array<*>) it.toList() as List<RankingEntry>
       else null
     }
   }else 
     null
  
  initHtml(ranking)
}

fun initHtml(ranking: List<RankingEntry>? = null) {
  console.log(ranking)
  val mainMenu = document.getElementById("start-menu") as HTMLDivElement
  val startButton = document.getElementById("start-btn") as HTMLButtonElement
  val rankingData = ranking ?: JSON.parse<Array<RankingEntry>>(window.localStorage[RANKING_KEY] ?: "[]").toList()
  updateRanking(rankingData)
  
  startButton.onclick = {
    val canvas = document.getElementById("game-canvas") as HTMLCanvasElement
    val context = canvas.getContext("2d")!! as CanvasRenderingContext2D
    val canvasDimensions = Dimensions(canvas.width,canvas.height)
    context.clearRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
    mainMenu.style.display = "none"
    canvas.style.display = "block"

      (document.getElementById("game-audio") as HTMLAudioElement?)?.run {
        volume = 0.2
        play()
      }
      gameStart(context,canvasDimensions,rankingData)
    }
  }


/**
 * PARTE IMPURA.
 * estado da última tecla de direção apertada pelo usuário.
 * modificado no evento "keydown" definido no [gameStart]
 */
var desiredDir = Direction.LEFT

fun gameStart(context: CanvasRenderingContext2D, canvasDimensions: Dimensions, ranking: List<RankingEntry>){
  //Calcula as dimensões da tela de jogo.
  val boardDimensions = canvasDimensions / CANVAS_SCALE
  
  //Gatilho disparado sempre que qualquer tecla seja pressionada no teclado do usuário
  window.addEventListener("keydown",{event -> if(event is KeyboardEvent) {
    /** Caso a tecla pressionada seja um comando de movimento válido, redefine a [desiredDir] para a direção escolhida **/
    when (event.key) {
      "ArrowUp", "w" -> {
        desiredDir = Direction.UP
      }
      "ArrowDown", "s" -> {
        desiredDir = Direction.DOWN
      }
      "ArrowLeft", "a" -> {
        desiredDir = Direction.LEFT
      }
      "ArrowRight", "d" -> {
        desiredDir = Direction.RIGHT
      }
      else -> Unit
    }
  } 
  })
  
  //Incializa um tabuleiro aleatório
  val board = initRandomBoard(boardDimensions.width,boardDimensions.height)

  console.log(board)
  console.log(board.toString())
  //Começa o loop de jogo, isso é uma função recursiva que roda indefinidamente até que o jogo acabe
  updateScore(board.player)
  gameLoop(board,context,canvasDimensions,ranking)
}



/**
 * @param boardState lista com a posição e estado atual de todas as peças no tabuleiro
 * @param context usado para imprimir as informações do tabuleiro na tela **(pintar os pixeis no canvas)**
 * @param canvasDim dimensões do canvas **(Definido na primeira iteração, não mudar)**
 */
fun gameLoop(boardState: Board, context: CanvasRenderingContext2D,canvasDim: Dimensions,ranking: List<RankingEntry>){
  /**
  * Caso a direção do player seja alterada entre as renderizações (AÇÃO IMPURA)
  * gera uma cópia da peça do jogador com essa informação atualizada e usa ela no lugar
  */
  val player = boardState.player.let { 
    if(desiredDir == it.direction || it.direction.isInverse(desiredDir)) it
    else it.copy(direction = desiredDir)
  }
  
  //Imprime no canvas a tela atual
  renderBoard(context,boardState)
  
  if(player.life <= 0){
    endGame(context, canvasDim,boardState,ranking)
    return
  }
  
  val newBoard = movePlayer(boardState,player)
  val scoreUpdated = newBoard.player.score > player.score
  window.setTimeout({
    if(scoreUpdated) updateScore(newBoard.player)
    gameLoop(newBoard,context,canvasDim,ranking)
  }, max(10,(100 - (2 * player.tail.size))))
}

fun endGame(context: CanvasRenderingContext2D, canvasDim: Dimensions,boardState: Board,ranking: List<RankingEntry>){
  console.log(boardState.toString())
  context.fillStyle = "#000000CC"
  context.shadowBlur = 0.5
  context.fillRect(0.0, 0.0, canvasDim.width.toDouble(),canvasDim.height.toDouble())
  (document.getElementById("end-screen") as HTMLDivElement).style.display = "flex"
  (document.getElementById("game-score") as HTMLSpanElement).innerText = boardState.player.score.toString()
  (document.getElementById("game-canvas") as HTMLCanvasElement).style.display = "none"
  val player = boardState.player
  val win = (ranking.isNotEmpty() && player.score > ranking[0].score)
  (document.getElementById("game-audio") as HTMLAudioElement?)?.pause()
  (document.getElementById("game-audio-fx") as HTMLAudioElement?)?.run{
    src = if(win) "on_win.wav" else "on_lose.wav"
    volume = 0.2
    play()
  }
  
  if(win){
    val endImg = (document.getElementById("end-img") as HTMLImageElement)
    endImg.src = "./congratulations.svg"
    endImg.alt = "Congratulations Text with Party Emojis"
  }

  (document.getElementById("end-form") as HTMLFormElement).onsubmit = submit@{ it ->
    it.preventDefault()
    if(it.target !is HTMLFormElement) {
      return@submit null
    }
    
    val playerName = ((it.target as HTMLFormElement)["name"] as HTMLInputElement).value
    val scoreEntry = RankingEntry(playerName,player.score)
    console.log(scoreEntry)
    
    //Logica de salvar no Localstorage
    window.localStorage[RANKING_KEY] = JSON.stringify((ranking + scoreEntry).sortedByDescending { it.score })
    //Salvar na Nuvem
    val jsonData = JSON.stringify(scoreEntry)
    window.fetch("https://lambda-snake.netlify.app/.netlify/functions/update-score", RequestInit(method = "POST", body = jsonData)).then { 
      window.location.reload()
    }
    return@submit null
  }
}
