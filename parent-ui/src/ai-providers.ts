export type Provider={id:string,name:string,baseUrl:string,model:string,visionModel:string,note:string,keyUrl:string};

// Must stay in sync with GradingService.providers() in the Android app; custom 放最后。
export const builtinProviders:Provider[]=[
 {id:'deepseek',name:'DeepSeek',baseUrl:'https://api.deepseek.com/v1',model:'deepseek-flash',visionModel:'deepseek-flash',note:'性价比高，支持文字与手写图片（开启“模型支持图像”）',keyUrl:'https://platform.deepseek.com/api_keys'},
 {id:'dashscope',name:'阿里云百炼（通义千问）',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',model:'qwen3.8-flash',visionModel:'qwen3.8-flash',note:'',keyUrl:'https://bailian.console.aliyun.com/?apiKey=1'},
 {id:'zhipu',name:'智谱 GLM',baseUrl:'https://open.bigmodel.cn/api/paas/v4',model:'glm-4.7-flash',visionModel:'glm-4.6v-flash',note:'有免费模型',keyUrl:'https://open.bigmodel.cn/usercenter/apikeys'},
 {id:'moonshot',name:'月之暗面 Kimi',baseUrl:'https://api.moonshot.cn/v1',model:'kimi-k3.1-pro',visionModel:'kimi-k3.1-pro',note:'',keyUrl:'https://platform.moonshot.cn/console/api-keys'},
 {id:'siliconflow',name:'硅基流动',baseUrl:'https://api.siliconflow.cn/v1',model:'Qwen/Qwen3-8B',visionModel:'Qwen/Qwen3-VL-8B-Instruct',note:'',keyUrl:'https://cloud.siliconflow.cn/account/ak'},
 {id:'volcengine',name:'火山方舟（豆包）',baseUrl:'https://ark.cn-beijing.volces.com/api/v3',model:'doubao-seed-2-1-lite-260915',visionModel:'doubao-seed-2-1-lite-260915',note:'模型名填写模型 ID（如 doubao-seed-2-1-lite-260915）；也兼容推理接入点 ID',keyUrl:'https://console.volcengine.com/ark'},
 {id:'openrouter',name:'OpenRouter',baseUrl:'https://openrouter.ai/api/v1',model:'openai/gpt-6-luna',visionModel:'openai/gpt-6-luna',note:'',keyUrl:'https://openrouter.ai/keys'},
 {id:'openai',name:'OpenAI',baseUrl:'https://api.openai.com/v1',model:'gpt-6-luna',visionModel:'gpt-6-luna',note:'',keyUrl:'https://platform.openai.com/api-keys'},
 {id:'ollama',name:'本地 Ollama / LM Studio',baseUrl:'http://192.168.1.10:11434/v1',model:'qwen3:8b',visionModel:'qwen3-vl:8b',note:'局域网内的电脑，地址改成那台电脑的 IP；无需 API Key',keyUrl:''},
 {id:'custom',name:'自定义（OpenAI 兼容）',baseUrl:'',model:'',visionModel:'',note:'',keyUrl:''},
];
