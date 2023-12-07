# app.py
# -*- coding: utf-8 -*-
from flask import Flask, request, jsonify
from naoqi import ALProxy
import time


app = Flask(__name__)
motion_proxy = ALProxy("ALMotion", "192.168.0.104", 9559)
text_to_speech = ALProxy("ALTextToSpeech", "192.168.0.104", 9559)
tts = ALProxy("ALTextToSpeech", "192.168.0.104", 9559)


@app.route('/')
def index():
    return "Hello, this is the index page!"

@app.route('/control_robot', methods=['POST'])
def control_robot():
    data = request.get_json()
    print("Received data:", data)
    command = data.get('command')

    if command == 'raise_hand':
        motion_proxy.setAngles("RShoulderPitch", 1.5, 0.1)
        time.sleep(1)
        motion_proxy.setAngles("LShoulderPitch", 0.0, 0.1)
        tts.say("rasing my hand")
        return jsonify({"response": "手臂举起！"})
    elif command == 'turn_around':
        motion_proxy.setAngles("HeadYaw", 1.0, 0.1)
        time.sleep(2)
        motion_proxy.setAngles("HeadYaw", 0.0, 0.1)
        tts.say("turning my head")
        return jsonify({"response": "头部转动!"})
    elif command == 'walk_forward':
        motion_proxy.moveInit()
        motion_proxy.move(0.1, 0, 0)
        return jsonify({"response": "向前走!"})

    else:
        return jsonify({"response": "未知命令"})

@app.route('/silent_and_wave', methods=['POST'])
def silent_and_wave():
    # 设置音量为0，Pepper保持静默
    text_to_speech.setVolume(0.0)

    # 胳膊持续随机晃动，你可以根据需要调整时间和步进
    while True:
        motion_proxy.setAngles("RShoulderPitch", 1.5, 0.1)
        time.sleep(1)
        motion_proxy.setAngles("RShoulderPitch", 0.0, 0.1)

    return jsonify({"response": "Pepper is silent and waving!"})

if __name__ == '__main__':
    app.run(host='192.168.0.101', port=5000)
