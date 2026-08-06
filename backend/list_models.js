const { GoogleGenAI } = require('@google/genai');
const apiKey = process.env.GEMINI_API_KEY;
if (!apiKey) {
  console.error('GEMINI_API_KEY is required to list AI models. Set it in your environment.');
  process.exit(1);
}
const ai = new GoogleGenAI({ apiKey });
ai.models.list().then(res => { console.log(JSON.stringify(res, null, 2)); }).catch(console.error);
