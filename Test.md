1.启动Ollama serve 
> pkill ollama  #杀死已存在的 ollama进程
> cd ~          
> export OLLAMA_HOST=0.0.0.0:11434
> ./bin/ollama serve

2.启动Python服务
> cd ~/ollama_web_chat/
> export OLLAMA_BASE_URL=http://127.0.0.1:11434
> python3 app.py

3.查看设备IP

> zkjr@linaro-alip:~$ ip addr show


浏览器输入：http:/设备IP:3000/