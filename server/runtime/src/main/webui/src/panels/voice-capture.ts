import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

@customElement('voice-capture')
export class VoiceCapture extends LitElement {
  @state() private recording = false;
  @state() private sessionId = '';
  private mediaRecorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private _cleanups: (() => void)[] = [];

  static styles = css`
    :host { display: flex; align-items: center; gap: 8px; padding: 0 8px; }
    button {
      border: none; border-radius: 50%; width: 32px; height: 32px;
      cursor: pointer; font-size: 16px; transition: background 0.2s;
    }
    button.record { background: var(--voice-idle, #666); color: white; }
    button.record.active { background: var(--voice-active, #e53935); animation: pulse 1.5s infinite; }
    .status { font-size: 12px; color: var(--text-secondary, #aaa); }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }
  `;

  connectedCallback() {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent(document, 'voice-transcript-ready', () => {
        this.recording = false;
      })
    );
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  configure(props: { sessionId?: string }) {
    if (props.sessionId) this.sessionId = props.sessionId;
  }

  private async toggleRecording() {
    if (this.recording) {
      this.mediaRecorder?.stop();
    } else {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.mediaRecorder = new MediaRecorder(stream);
      this.chunks = [];
      this.mediaRecorder.ondataavailable = (e) => this.chunks.push(e.data);
      this.mediaRecorder.onstop = () => this.uploadAudio();
      this.mediaRecorder.start();
      this.recording = true;
    }
  }

  private async uploadAudio() {
    const blob = new Blob(this.chunks, { type: 'audio/webm' });
    const form = new FormData();
    form.append('audio', blob, 'recording.webm');
    form.append('sessionId', this.sessionId);

    try {
      await fetch('/api/voice/upload', { method: 'POST', body: form });
    } catch (e) {
      console.error('Voice upload failed:', e);
    }
    this.recording = false;
  }

  render() {
    return html`
      <button class="record ${this.recording ? 'active' : ''}"
              @click=${this.toggleRecording}
              title=${this.recording ? 'Stop recording' : 'Start recording'}>
        ${this.recording ? '⏹' : '🎤'}
      </button>
      <span class="status">${this.recording ? 'Recording...' : ''}</span>
    `;
  }
}
