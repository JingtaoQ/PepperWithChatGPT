package com.malkinfo.chatgpts

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.*
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.withContext
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import java.util.Locale
import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.media.audiofx.NoiseSuppressor
import android.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.*
import okhttp3.RequestBody.Companion.toRequestBody


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val API_KEY = "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"
    private lateinit var recyclerView: RecyclerView
    private lateinit var welcomeText: TextView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var messageList: MutableList<Message>
    private lateinit var messageAdapter: MessageAdapter
    private val client = OkHttpClient()
    private lateinit var sharedPreferences: SharedPreferences
    private var conversationHistory = ""
    private lateinit var textToSpeech: TextToSpeech

    private lateinit var language: Locale

    private val AZURE_SUBSCRIPTION_KEY = "84e1d83c2db94882943af87465f0909e"
    private val speechConfig by lazy {
        SpeechConfig.fromSubscription(AZURE_SUBSCRIPTION_KEY, "eastus").apply {
            setSpeechSynthesisVoiceName("en-US-AmberNeural")
        }
    }


    private var speechRecognizer: SpeechRecognizer? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var isSpeechSynthesisPlaying = false
    private var azureSynthesizer: SpeechSynthesizer? = null
    private var azureSynthesisThread: Thread? = null

    // speech to text
    private lateinit var voiceButton: ImageButton
    private lateinit var noiseSuppressor: NoiseSuppressor
    private val REQ_CODE_SPEECH_INPUT = 100000
    private val TIMEOUT_DURATION = 200000
    private val MAX_VOICE_INPUT_LENGTH = 100



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageList = ArrayList()
        recyclerView = findViewById(R.id.recycler_view)
        welcomeText = findViewById(R.id.welcome_text)
        messageEditText = findViewById(R.id.message_edit_text)
        sendButton = findViewById(R.id.send_bt)
        messageAdapter = MessageAdapter(messageList)
        voiceButton = findViewById(R.id.voice_bt)
        recyclerView.adapter = messageAdapter
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager

        sharedPreferences = getPreferences(Context.MODE_PRIVATE)

        // 初始化 TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        SpeechConfig.fromSubscription(AZURE_SUBSCRIPTION_KEY, "eastus")
        azureSynthesizer = SpeechSynthesizer(speechConfig)


        voiceButton.setOnClickListener {
            // 如果 textToSpeech 已经初始化并且正在朗读，则停止朗读
            if (::textToSpeech.isInitialized && textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }

            // 如果 Azure 语音合成器正在播放，停止它
            if (isSpeechSynthesisPlaying) {
                // 停止 Azure 语音合成
                stopAzureSynthesisThread()

                // 释放 Azure 语音合成器
                releaseAzureSpeechSynthesizer()

                // 重置播放状态
                isSpeechSynthesisPlaying = false
            }

            // 停止任何正在进行的语音识别
            stopSpeechRecognition()

            // 释放降噪处理器
            releaseNoiseSuppressor()

            // 延迟一段时间后初始化降噪处理器
            voiceButton.postDelayed({
                initNoiseSuppression()

                // 提示进行新的语音输入
                promptSpeechInput()
            }, 500) // 500 毫秒延迟，你可以根据需要调整
        }


        /* sendButton.setOnClickListener {
            val question = messageEditText.text.toString().trim { it <= ' ' }

            // 如果用户发送的内容是 "raise your hand"，则发送命令给机器人
            if (question.toLowerCase(Locale.getDefault()) == "raise your hand" && !isPythonCommandSent) {
                sendCommandToRobot("raise_hand")
                isPythonCommandSent = true
            } else {
                // 如果不是 "raise your hand"，则进行常规的 ChatGPT 交互
                addToChat(question, Message.SENT_BY_ME)
                messageEditText.setText("")

                // welcomeText.visibility = View.GONE
                GlobalScope.launch(Dispatchers.IO) {
                    val language = detectLanguageAsync(question)

                    // 设置TextToSpeech的语言
                    setTtsLanguage(language)

                    val prompt = getConversationHistory() + "\nUser: $question\nChatGPT:"

                    // 使用协程进行网络请求
                    callAPI(prompt)
                }

                welcomeText.visibility = View.GONE
            }
        } */
        sendButton.setOnClickListener {
            sendMessage()
        }


    }

    // 释放 SpeechSynthesizer
    private fun releaseAzureSpeechSynthesizer() {
        try {
            synthesizer?.close()
        } catch (e: Exception) {
            Log.e("AzureTTS", "释放 Azure 语音合成器时发生错误: ${e.message}")
        }
    }

    private fun stopAzureSynthesisThread() {
        try {
            azureSynthesisThread?.interrupt()
            azureSynthesisThread = null

            // 释放 Azure 语音合成器
            releaseAzureSpeechSynthesizer()
        } catch (e: Exception) {
            Log.e("AzureTTS", "停止 Azure 语音合成线程时发生错误: ${e.message}")
        }
    }

    //speech to text
    private fun promptSpeechInput() {
        stopSpeechRecognition()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speaking....")

        // 增加超时时长，可以根据需要调整
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, TIMEOUT_DURATION)

        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

        try {
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT)
        } catch (e: ActivityNotFoundException) {
            Log.e("SpeechInput", "不支持语音输入", e)
            Toast.makeText(applicationContext, "不支持语音输入", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SpeechInput", "启动语音输入时发生错误", e)
            Toast.makeText(applicationContext, "发生错误", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeechRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }

        // 添加释放 Azure 语音合成器的逻辑
        releaseAzureSpeechSynthesizer()
    }


    private fun initNoiseSuppression() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioSessionId = audioManager.generateAudioSessionId()

            // 检查设备是否支持降噪
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.setEnabled(true)
            } else {
                Log.e("NoiseSuppression", "设备不支持降噪")
            }
        } catch (e: Exception) {
            Log.e("NoiseSuppression", "初始化降噪时发生错误: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_CODE_SPEECH_INPUT -> {
                if (resultCode == RESULT_OK && null != data) {
                    val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (result != null && result.isNotEmpty()) {
                        val voiceInput = result[0]

                        // 处理语音输入时长超过限制的情况
                        if (voiceInput.length > MAX_VOICE_INPUT_LENGTH) {
                            Log.e("SpeechInput", "语音输入时长过长: $voiceInput")
                            Toast.makeText(applicationContext, "语音输入时长过长，请重新尝试", Toast.LENGTH_SHORT).show()
                            return
                        }

                        Log.d("SpeechInput", "识别成功: $voiceInput")
                        messageEditText.setText(voiceInput)

                        // 手动触发发送消息的逻辑
                        sendMessage()
                    }
                } else if(resultCode == RecognizerIntent.RESULT_AUDIO_ERROR) {
                    // 处理网络超时错误
                    Log.e("SpeechInput", "语音输入错误 RESULT_AUDIO_ERROR")
                    Toast.makeText(applicationContext, "语音输入超时，请检查网络连接", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("SpeechInput", "识别失败，结果代码为：$resultCode")
                    val errorBundle = data?.extras
                    Log.e("SpeechInput", "额外的错误信息: $errorBundle")

                    if (errorBundle != null) {
                        for (key in errorBundle.keySet()) {
                            val value = errorBundle.get(key)
                            Log.e("SpeechInput", "Key: $key, Value: $value")
                        }
                    }
                    // 处理其他错误情况，如果需要的话
                }
            }
        }
    }

    private fun releaseNoiseSuppressor() {
        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.e("NoiseSuppression", "释放降噪处理器时发生错误: ${e.message}")
        }
    }

    // 实现 TextToSpeech.OnInitListener 接口
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 设置语音引擎的语言为默认语言
            val result = textToSpeech.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language is not available or not supported")
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    // 在 MainActivity 类中添加 detectLanguage 方法
    private suspend fun detectLanguageAsync(text: String): Locale {
        return withContext(Dispatchers.IO) {
            val languageIdentifier = LanguageIdentification
                .getClient(
                    LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(0.34f)
                        .build()
                )

            val identificationTask = languageIdentifier.identifyLanguage(text)

            try {
                val languageCode = Tasks.await(identificationTask)

                language = if (languageCode != "und") {
                    Locale(languageCode)
                } else {
                    Log.w("LanguageDetection", "Unable to determine the language of the text: $text")
                    Locale.getDefault()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("LanguageDetection", "An error occurred during language detection: ${e.message}")
                language = Locale.getDefault()
            }

            return@withContext language
        }
    }



    private fun setTtsLanguage(language: Locale) {
        Log.d("TTS", "设置语言: ${language.language}")

        val result = textToSpeech.setLanguage(language)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "语言不可用或不受支持")
            // 处理所选语言不可用或不受支持的情况
        }
    }

    private fun speak(text: String, language: Locale) {
        GlobalScope.launch(Dispatchers.IO) {
            // 如果正在播放，则停止
            if (isSpeechSynthesisPlaying) {
                stopAzureSynthesisThread()
                isSpeechSynthesisPlaying = false
            }
            // Azure and TTS
            if (language.language == "en") {
                azureSynthesisThread = Thread(Runnable {
                    val synthesizer = SpeechSynthesizer(speechConfig)
                    val result = synthesizer.SpeakText(text)

                    if (result.reason == ResultReason.SynthesizingAudioCompleted) {
                        isSpeechSynthesisPlaying = true
                    } else if (result.reason == ResultReason.Canceled) {
                        Log.d("AzureTTS", "Speech synthesis canceled: ${result.reason}")
                    } else {
                        Log.e("AzureTTS", "Speech synthesis failed: ${result.reason}")
                    }
                })

                azureSynthesisThread?.start()
            } else {
                val localTextToSpeech = textToSpeech
                localTextToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                isSpeechSynthesisPlaying = true
            }
        }
    }

    private fun addResponse(response: String?) {
        runOnUiThread {
            messageList.removeAt(messageList.size - 1)
            addToChat(response!!, Message.SENT_BY_BOT)

            // 将 GPT-3.5 Turbo 的响应文本转换为语音并播放
            speak(response, language)
        }
    }



    private fun addToChat(message: String, sentBy: String) {
        runOnUiThread {
            conversationHistory += if (sentBy == Message.SENT_BY_ME) "\nUser: $message" else "\nChatGPT: $message"
            saveConversationHistory(conversationHistory)

            messageList.add(Message(message, sentBy))
            messageAdapter.notifyDataSetChanged()
            recyclerView.smoothScrollToPosition(messageAdapter.itemCount)
        }
    }

    private fun getConversationHistory(): String {
        return sharedPreferences.getString("conversation_history", "") ?: ""
    }

    private fun saveConversationHistory(history: String) {
        sharedPreferences.edit().putString("conversation_history", history).apply()
    }

    private fun callAPI(prompt: String) {
        messageList.add(Message("Typing...", Message.SENT_BY_BOT))
        val jsonBody = JSONObject()
        try {
            jsonBody.put("model", "gpt-3.5-turbo")
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().put("role", "system").put("content", "You are a helpful assistant."))
            messagesArray.put(JSONObject().put("role", "user").put("content", prompt))
            jsonBody.put("messages", messagesArray)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        val body: RequestBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonBody.toString())
        val request: Request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $API_KEY")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                addResponse("Failed to load response due to ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        addResponse("Failed to load response. HTTP Code: ${response.code}")
                        return
                    }

                    val jsonObject = JSONObject(response.body!!.string())
                    val choicesArray = jsonObject.getJSONArray("choices")

                    if (choicesArray.length() > 0) {
                        val result = choicesArray.getJSONObject(0).optString("message", "")
                        val cleanResult = extractChatContent(result)
                        addResponse(cleanResult)
                    } else {
                        addResponse("No response from GPT-3.5 Turbo.")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    addResponse("Error processing GPT-3.5 Turbo response: ${e.message}")
                }
            }
        })
    }

    private fun extractChatContent(result: String): String {
        try {
            val jsonObject = JSONObject(result)
            val content = jsonObject.getString("content")
            return content
        } catch (e: JSONException) {
            e.printStackTrace()
            return "Error extracting content"
        }
    }

    companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    }

    private var isPythonCommandSent = false

    private fun sendMessage() {
        val question = messageEditText.text.toString().trim { it <= ' ' }

        Log.d("sendMessage", "用户输入: $question")

        // 如果用户输入是 "what's your name?"，则直接回复 "I'm Pepper"
        if (question.toLowerCase(Locale.getDefault()) == "what's your name" || question.toLowerCase(Locale.getDefault()) == "can you tell me your name" ) {
            addToChat(question, Message.SENT_BY_ME)
            val response = "I'm Pepper,I'm happy to help you"
            addToChat(response, Message.SENT_BY_BOT)

            // 将回复文本转换为语音并播放
            speak(response, language)

            // 清空输入框
            messageEditText.setText("")
            return
        }

        // 如果用户输入是 "raise your hand"，则发送命令给机器人
        if (question.toLowerCase(Locale.getDefault()) == "raise your hand" && !isPythonCommandSent) {
            addToChat(question, Message.SENT_BY_ME)
            Log.d("sendMessage", "发送 Python 命令: raise_hand")
            // 发送 "raise your hand" 命令并设置 isPythonCommandSent 为 true
            sendCommandToRobot("raise_hand")
            isPythonCommandSent = true
        }
        if (question.toLowerCase(Locale.getDefault()) == "turn around" && !isPythonCommandSent) {
            addToChat(question, Message.SENT_BY_ME)
            Log.d("sendMessage", "发送 Python 命令: turn_around")
            // 发送 "raise your hand" 命令并设置 isPythonCommandSent 为 true
            sendCommandToRobot("turn_around")
            isPythonCommandSent = true
            return
        } else {
            Log.d("sendMessage", "发送消息到 ChatGPT: $question")
            addToChat(question, Message.SENT_BY_ME)
            messageEditText.setText("")
            sendSilentAndWaveCommand()


            // welcomeText.visibility = View.GONE
            GlobalScope.launch(Dispatchers.IO) {
                val language = detectLanguageAsync(question)

                // 设置 TextToSpeech 的语言
                setTtsLanguage(language)

                val prompt = getConversationHistory() + "\nUser: $question\nChatGPT:"

                // 使用协程进行网络请求，只有当用户输入不是 "raise your hand" 且 isPythonCommandSent 为 false 时才会调用 ChatGPT 的 API
                if (!isPythonCommandSent) {
                    callAPI(prompt)
                } else {
                    // 如果是 "raise your hand" 且 isPythonCommandSent 为 true，则重置 isPythonCommandSent 为 false
                    isPythonCommandSent = false
                }
            }

            welcomeText.visibility = View.GONE
        }
    }

    private fun sendSilentAndWaveCommand() {
        val url = "http://192.168.0.101:5000/silent_and_wave"
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, ""))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 处理失败
                Log.e("sendSilentAndWave", "Failed to send silent_and_wave command to robot: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                // 根据需要处理响应数据
                Log.d("sendSilentAndWave", "Response from robot (silent_and_wave): $responseData")
            }
        })
    }


    private fun sendCommandToRobot(command: String) {
        val url = "http://192.168.0.101:5000/control_robot"
        val client = OkHttpClient()
        val json = JSONObject().put("command", command).toString()
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 处理失败
                Log.e("sendCommandToRobot", "Failed to send command to robot: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                // 根据需要处理响应数据
                Log.d("sendCommandToRobot", "Response from robot: $responseData")
                // isPythonCommandSent = true
            }
        })
    }

    override fun onDestroy() {
        releaseNoiseSuppressor()
        stopSpeechRecognition()
        releaseAzureSpeechSynthesizer()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
