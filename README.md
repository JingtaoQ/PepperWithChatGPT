# PepperWithChatGPT

### Creating an Application with Kotlin

#### ML kit + Speak
The comprehensive software system seamlessly integrates MLKIT's language identification to precisely determine the language of the speech output. In the event of an English query, the system seamlessly calls upon Microsoft Azure for efficient broadcasting. In contrast, for queries in any other language, the software leverages Android's voice playback capabilities to deliver a nuanced and natural response.

>install dependencies on the build.gradle(mlkit, language-identification, language-detection, google-cloud-speech, micsoft-speech, eic)

#### Noisedecrease + logic in chat history
Concurrently, the system incorporates advanced noise reduction techniques to enhance the clarity of speech input, ensuring a robust and effective communication channel.

Moreover, the chat history undergoes intricate processing to maintain a coherent and contextually relevant conversation. This processing not only organizes the dialogue logically but also enhances the overall user experience.
#### Python + Flask
Underpinning these functionalities is a powerful backend infrastructure built on the Flask framework. The backend is deployed at 192.168.0.101:5000, serving as the control center for the bot's behavior. This includes managing dynamic actions such as raising hands and turning heads, contributing to a more immersive and engaging interaction with users.

#### Install on the robot pepper
Select the correct targetSdk, the minimum version number of the installed device
> targetSdk 27 for the Pepper(2018)
