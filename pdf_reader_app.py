import gradio as ui
import soundfile as sf
import os
import numpy as np
import urllib.request
from pypdf import PdfReader
from kokoro_onnx import Kokoro

# Paths to store the model locally
MODEL_DIR = os.path.expanduser("~/Desktop/PDFReader/kokoro_files")
os.makedirs(MODEL_DIR, exist_ok=True)

MODEL_PATH = os.path.join(MODEL_DIR, "kokoro-v1.0.onnx")
VOICES_PATH = os.path.join(MODEL_DIR, "voices-v1.0.bin")

# Reliable direct-download URLs
MODEL_URL = "https://github.com"
VOICES_URL = "https://github.com"

def setup_kokoro():
    # Use custom headers to prevent GitHub from cutting off the download streams
    opener = urllib.request.build_opener()
    opener.addheaders = [('User-agent', 'Mozilla/5.0')]
    urllib.request.install_opener(opener)

    if not os.path.exists(MODEL_PATH) or os.path.getsize(MODEL_PATH) < 1000000:
        print("Downloading Kokoro-82M model structure (approx. 80MB)...")
        urllib.request.urlretrieve(MODEL_URL, MODEL_PATH)
    if not os.path.exists(VOICES_PATH):
        print("Downloading Kokoro-82M voice assets...")
        urllib.request.urlretrieve(VOICES_URL, VOICES_PATH)
    return Kokoro(MODEL_PATH, VOICES_PATH)

# Initialize the model engine safely
kokoro_engine = setup_kokoro()

def read_pdf(pdf_file, voice_choice, speed):
    if pdf_file is None:
        return "Please upload a PDF file first.", None
    
    try:
        # 1. Extract text layout from the document
        reader = PdfReader(pdf_file.name)
        full_text = ""
        for page in reader.pages:
            text = page.extract_text()
            if text:
                full_text += text + "\n"
        
        if not full_text.strip():
            return "Could not extract any readable text from this PDF.", None

        # 2. Extract clean voice string name (e.g., "af_bella")
        clean_voice = voice_choice.split()[0]
        
        # 3. Create audio array using the ONNX architecture
        samples, sample_rate = kokoro_engine.create(
            full_text, 
            voice=clean_voice, 
            speed=speed, 
            lang="en-us"
        )
        
        # 4. Write out to crisp 24kHz audio track
        output_filename = "kokoro_output.wav"
        sf.write(output_filename, samples, sample_rate)
            
        preview_text = full_text[:500] + ("..." if len(full_text) > 500 else "")
        return f"Successfully processed! Text Preview:\n\n{preview_text}", output_filename

    except Exception as e:
        return f"An error occurred: {str(e)}", None

# Build UI Layout
with ui.Blocks(title="Kokoro-82M AI PDF Reader") as demo:
    ui.Markdown("# 🎧 Kokoro-82M Local PDF Reader (Free & Unlimited)")
    ui.Markdown("Experience tier-1 premium neural voices completely offline on Fedora.")
    
    with ui.Row():
        with ui.Column():
            file_input = ui.File(label="Upload PDF", file_types=[".pdf"])
            voice_dropdown = ui.Dropdown(
                choices=["af_bella (Female)", "af_nicole (Female)", "am_adam (Male)", "am_michael (Male)", "bf_emma (British)", "bm_george (British)"], 
                value="af_bella (Female)", 
                label="Voice Profile"
            )
            speed_slider = ui.Slider(minimum=0.5, maximum=2.0, value=1.0, step=0.1, label="Reading Speed")
            submit_btn = ui.Button("Convert PDF to Speech", variant="primary")
            
        with ui.Column():
            status_output = ui.Textbox(label="Status / Text Preview", interactive=False, lines=6)
            audio_output = ui.Audio(label="Listen Here", type="filepath")

    submit_btn.click(
        fn=read_pdf, 
        inputs=[file_input, voice_dropdown, speed_slider], 
        outputs=[status_output, audio_output]
    )

if __name__ == "__main__":
    demo.launch(server_name="127.0.0.1", server_port=7860)
