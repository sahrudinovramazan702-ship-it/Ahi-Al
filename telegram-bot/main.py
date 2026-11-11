import os
import logging
from collections import defaultdict, deque

import httpx
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from telegram import Update
from telegram.ext import Application, CommandHandler, MessageHandler, ContextTypes, filters

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("ahi-ai-bot")

BOT_TOKEN = os.environ["BOT_TOKEN"]
OPENROUTER_API_KEY = os.environ["OPENROUTER_API_KEY"]
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
PUBLIC_URL = os.getenv("RENDER_EXTERNAL_URL", "").rstrip("/")
WEBHOOK_SECRET = os.getenv("WEBHOOK_SECRET", "ahi-ai-webhook-secret")
MODEL = os.getenv("AI_MODEL", "openrouter/free")

app = FastAPI()
telegram_app = Application.builder().token(BOT_TOKEN).build()
histories = defaultdict(lambda: deque(maxlen=24))

SYSTEM_PROMPT = (
    "Ты АХИ AI — дружелюбный, полезный универсальный помощник. "
    "Отвечай по-русски, если пользователь пишет по-русски. "
    "Будь точным, честным и практичным. Если не знаешь — скажи."
)

async def ai_reply(user_id: int, text: str) -> str:
    history = histories[user_id]
    history.append({"role": "user", "content": text})
    messages = [{"role": "system", "content": SYSTEM_PROMPT}, *list(history)]
    async with httpx.AsyncClient(timeout=90) as client:
        response = await client.post(
            "https://openrouter.ai/api/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {OPENROUTER_API_KEY}",
                "Content-Type": "application/json",
                "HTTP-Referer": PUBLIC_URL or "https://telegram.org",
                "X-Title": "Ahi AI Telegram Bot",
            },
            json={"model": MODEL, "messages": messages, "temperature": 0.7},
        )
        response.raise_for_status()
        data = response.json()
    answer = data["choices"][0]["message"]["content"].strip()
    history.append({"role": "assistant", "content": answer})
    return answer or "Не получилось сформировать ответ. Попробуй ещё раз."

async def send_long(update: Update, text: str):
    for i in range(0, len(text), 3900):
        await update.effective_message.reply_text(text[i:i+3900])

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.effective_message.reply_text(
        "Привет! Я АХИ AI 🤖\n\n"
        "Могу отвечать на вопросы, помогать с учёбой, переводить, писать тексты и идеи. "
        "Напиши сообщение или отправь голосовое.\n\n"
        "/help — возможности\n/reset — очистить память диалога\n/voice — включить озвучку ответов"
    )

async def help_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.effective_message.reply_text(
        "Команды:\n/start — запуск\n/help — помощь\n/reset — новый диалог\n"
        "/voice — переключить голосовую озвучку.\n\n"
        "Также можно отправлять обычный текст и голосовые сообщения."
    )

async def reset(update: Update, context: ContextTypes.DEFAULT_TYPE):
    histories[update.effective_user.id].clear()
    await update.effective_message.reply_text("Историю этого диалога очистил. Начинаем заново ✨")

async def voice_toggle(update: Update, context: ContextTypes.DEFAULT_TYPE):
    context.user_data["voice_reply"] = not context.user_data.get("voice_reply", False)
    state = "включена" if context.user_data["voice_reply"] else "выключена"
    await update.effective_message.reply_text(f"Озвучка ответов {state}.")

async def text_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    message = update.effective_message
    user_id = update.effective_user.id
    prompt = (message.text or "").strip()
    if not prompt:
        return
    status = await message.reply_text("Думаю…")
    try:
        answer = await ai_reply(user_id, prompt)
        await status.delete()
        await send_long(update, answer)
        if context.user_data.get("voice_reply"):
            try:
                import edge_tts
                import tempfile
                import asyncio
                with tempfile.NamedTemporaryFile(suffix=".mp3") as audio:
                    communicate = edge_tts.Communicate(answer[:3000], "ru-RU-DmitryNeural")
                    await communicate.save(audio.name)
                    await message.reply_voice(voice=open(audio.name, "rb"))
            except Exception:
                log.exception("TTS failed")
                await message.reply_text("Не удалось озвучить ответ на этом сервере.")
    except Exception:
        log.exception("AI request failed")
        await status.edit_text("Не удалось получить ответ от ИИ. Проверь API-ключ или попробуй позже.")

async def voice_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    message = update.effective_message
    if not GROQ_API_KEY:
        await message.reply_text("Голосовой ввод подключается через GROQ_API_KEY. Пока можешь написать текстом.")
        return
    status = await message.reply_text("Распознаю голос…")
    try:
        voice = message.voice or message.audio
        file = await context.bot.get_file(voice.file_id)
        audio_bytes = await file.download_as_bytearray()
        async with httpx.AsyncClient(timeout=90) as client:
            result = await client.post(
                "https://api.groq.com/openai/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {GROQ_API_KEY}"},
                files={"file": ("voice.ogg", bytes(audio_bytes), "audio/ogg")},
                data={"model": "whisper-large-v3-turbo", "language": "ru", "response_format": "json"},
            )
            result.raise_for_status()
            transcript = result.json().get("text", "").strip()
        if not transcript:
            await status.edit_text("Не удалось распознать речь. Попробуй записать ещё раз.")
            return
        await status.edit_text(f"Распознано: {transcript}\n\nГотовлю ответ…")
        answer = await ai_reply(update.effective_user.id, transcript)
        await status.delete()
        await send_long(update, answer)
        if context.user_data.get("voice_reply"):
            try:
                import edge_tts, tempfile
                with tempfile.NamedTemporaryFile(suffix=".mp3") as audio:
                    await edge_tts.Communicate(answer[:3000], "ru-RU-DmitryNeural").save(audio.name)
                    await message.reply_voice(voice=open(audio.name, "rb"))
            except Exception:
                log.exception("TTS failed")
    except Exception:
        log.exception("Voice processing failed")
        await status.edit_text("Не получилось обработать голосовое. Попробуй ещё раз или напиши текстом.")

telegram_app.add_handler(CommandHandler("start", start))
telegram_app.add_handler(CommandHandler("help", help_cmd))
telegram_app.add_handler(CommandHandler("reset", reset))
telegram_app.add_handler(CommandHandler("voice", voice_toggle))
telegram_app.add_handler(MessageHandler(filters.VOICE | filters.AUDIO, voice_message))
telegram_app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, text_message))

@app.on_event("startup")
async def startup():
    await telegram_app.initialize()
    await telegram_app.start()
    if PUBLIC_URL:
        await telegram_app.bot.set_webhook(
            url=f"{PUBLIC_URL}/telegram/{WEBHOOK_SECRET}",
            secret_token=WEBHOOK_SECRET,
            allowed_updates=Update.ALL_TYPES,
        )
        log.info("Telegram webhook configured")
    else:
        log.warning("RENDER_EXTERNAL_URL is missing; webhook not configured")

@app.on_event("shutdown")
async def shutdown():
    if telegram_app.running:
        await telegram_app.stop()
    await telegram_app.shutdown()

@app.post("/telegram/{secret}")
async def telegram_webhook(secret: str, request: Request):
    if secret != WEBHOOK_SECRET:
        raise HTTPException(status_code=403, detail="Forbidden")
    payload = await request.json()
    update = Update.de_json(payload, telegram_app.bot)
    await telegram_app.process_update(update)
    return JSONResponse({"ok": True})

@app.get("/")
async def health():
    return {"status": "Ahi AI Telegram bot is running"}
