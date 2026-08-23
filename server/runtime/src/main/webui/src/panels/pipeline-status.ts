import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface PipelineStage { name: string; status: 'pending' | 'running' | 'complete' | 'failed'; }

@customElement('pipeline-status')
export class PipelineStatus extends LitElement {
  @state() private stages: PipelineStage[] = [];
  @state() private errorMessage = '';
  private _cleanups: (() => void)[] = [];

  static styles = css`
    :host { display: flex; align-items: center; gap: 6px; padding: 0 8px; font-size: 12px; }
    .stage { display: flex; align-items: center; gap: 3px; }
    .dot { width: 8px; height: 8px; border-radius: 50%; }
    .dot.pending { background: var(--text-secondary, #666); }
    .dot.running { background: var(--voice-active, #fb8c00); animation: blink 1s infinite; }
    .dot.complete { background: var(--success, #4caf50); }
    .dot.failed { background: var(--error, #e53935); }
    .error { color: var(--error, #e53935); font-size: 11px; }
    @keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
  `;

  connectedCallback() {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent(document, 'voice-transcript-ready', () => {
        this.stages = [
          { name: 'STT', status: 'complete' },
          { name: 'Cleanup', status: 'running' },
        ];
        this.errorMessage = '';
      }),
      onPagesEvent(document, 'note-created', () => {
        this.stages = [
          { name: 'STT', status: 'complete' },
          { name: 'Cleanup', status: 'complete' },
        ];
        setTimeout(() => { this.stages = []; }, 3000);
      }),
      onPagesEvent(document, 'pipeline-error', (payload: any) => {
        this.errorMessage = payload.error || 'Pipeline failed';
        this.stages = this.stages.map(s =>
          s.status === 'running' ? { ...s, status: 'failed' as const } : s);
      })
    );
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  render() {
    if (this.stages.length === 0 && !this.errorMessage) return html``;
    return html`
      ${this.stages.map(s => html`
        <span class="stage">
          <span class="dot ${s.status}"></span>
          ${s.name}
        </span>
      `)}
      ${this.errorMessage ? html`<span class="error">${this.errorMessage}</span>` : ''}
    `;
  }
}
