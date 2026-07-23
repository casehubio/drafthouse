import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface BrainstormOptionData {
  id: string;
  title: string;
  description: string;
  tradeoffs: string;
  status: string;
}

interface OptionsPayload {
  sessionId: string;
  options: BrainstormOptionData[];
  state: string;
}

interface ConvergedPayload extends OptionsPayload {
  selectedOptionId: string;
}

@customElement('brainstorm-options')
export class BrainstormOptions extends LitElement {
  @property() sessionId: string | null = null;

  @state() private _options: BrainstormOptionData[] = [];
  @state() private _sessionState: string = 'ACTIVE';
  @state() private _ended = false;
  @state() private _errorMessage: string | null = null;

  private _cleanups: (() => void)[] = [];
  private _errorTimeout: ReturnType<typeof setTimeout> | null = null;

  configure(props: Record<string, unknown>): void {
    if (props.sessionId !== undefined) {
      this.sessionId = props.sessionId as string;
      this._options = [];
      this._sessionState = 'ACTIVE';
      this._ended = false;
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<OptionsPayload>(document, 'brainstorm-options', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._options = payload.options;
          this._sessionState = payload.state;
        }
      }),
      onPagesEvent<ConvergedPayload>(document, 'brainstorm-converged', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._options = payload.options;
          this._sessionState = payload.state;
        }
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-ended', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._ended = true;
        }
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._options = [];
        this._sessionState = 'ACTIVE';
        this._ended = false;
      }),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
  }

  private async _patchStatus(optionId: string, status: string): Promise<void> {
    if (!this.sessionId) return;
    try {
      const res = await fetch(
        `/api/brainstorm/${this.sessionId}/options/${optionId}`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status }),
        });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ error: 'Request failed' }));
        this._showError(body.error || `Status ${res.status}`);
      }
    } catch {
      this._showError('Network error');
    }
  }

  private _showError(msg: string): void {
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
    this._errorMessage = msg;
    this._errorTimeout = setTimeout(() => {
      this._errorMessage = null;
      this._errorTimeout = null;
    }, 3000);
  }

  private _isActionable(status: string): boolean {
    return !this._ended && this._sessionState === 'ACTIVE'
        && status !== 'ELIMINATED' && status !== 'SELECTED';
  }

  static override styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow-y: auto;
      padding: 12px;
      gap: 12px;
      background: var(--bg);
      color: var(--ink);
      font-family: var(--font-sans, system-ui, sans-serif);
    }

    .summary {
      font-size: 12px;
      color: var(--muted);
      padding: 4px 0;
    }

    .card {
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 12px;
      background: var(--chrome);
    }

    .card.recommended {
      border-color: var(--accent, #2563eb);
      box-shadow: 0 0 0 1px var(--accent, #2563eb);
    }

    .card.eliminated { opacity: 0.5; }
    .card.eliminated .card-title { text-decoration: line-through; }

    .card.selected {
      border-color: var(--success, #16a34a);
      box-shadow: 0 0 0 2px var(--success, #16a34a);
    }

    .card-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    .card-title { font-weight: 600; font-size: 14px; flex: 1; }

    .status-badge {
      font-size: 10px;
      padding: 2px 6px;
      border-radius: 3px;
      text-transform: uppercase;
      font-weight: 600;
    }

    .status-badge.live { background: var(--chrome); color: var(--muted); }
    .status-badge.recommended { background: var(--accent, #2563eb); color: #fff; }
    .status-badge.explored { background: var(--info-bg, #dbeafe); color: var(--info, #1d4ed8); }
    .status-badge.eliminated { background: var(--error-bg, #fef2f2); color: var(--error, #dc2626); }
    .status-badge.selected { background: var(--success, #16a34a); color: #fff; }

    .card-body { font-size: 13px; line-height: 1.5; }
    .card-body p { margin: 4px 0; }

    .tradeoffs {
      font-size: 12px;
      color: var(--muted);
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px solid var(--border);
    }

    .actions { display: flex; gap: 6px; margin-top: 10px; }

    .actions button {
      font-size: 11px;
      padding: 3px 10px;
      border-radius: 3px;
      border: 1px solid var(--border);
      background: var(--chrome);
      color: var(--ink);
      cursor: pointer;
    }

    .actions button:hover:not(:disabled) { background: var(--accent-light); }
    .actions button:disabled { opacity: 0.4; cursor: default; }

    .banner {
      padding: 8px 12px;
      border-radius: 4px;
      font-size: 12px;
      text-align: center;
    }

    .banner.ended { background: var(--warning-bg, #fefce8); color: var(--warning, #a16207); }
    .banner.converged { background: var(--success-bg, #f0fdf4); color: var(--success, #16a34a); }

    .error-flash {
      padding: 6px 12px;
      font-size: 12px;
      color: var(--error, #dc2626);
      background: var(--error-bg, #fef2f2);
      border-radius: 4px;
    }

    .empty {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      color: var(--muted);
      font-size: 13px;
    }
  `;

  override render() {
    if (this._options.length === 0) {
      return html`<div class="empty">Waiting for options...</div>`;
    }

    const eliminated = this._options.filter(o => o.status === 'ELIMINATED').length;
    const recommended = this._options.filter(o => o.status === 'RECOMMENDED').length;
    const selected = this._options.filter(o => o.status === 'SELECTED').length;

    const parts: string[] = [`${this._options.length} options`];
    if (eliminated) parts.push(`${eliminated} eliminated`);
    if (recommended) parts.push(`${recommended} recommended`);
    if (selected) parts.push(`${selected} selected`);

    return html`
      <div class="summary">${parts.join(' · ')}</div>

      ${this._ended ? html`<div class="banner ended">Session ended</div>` : nothing}
      ${this._sessionState === 'CONVERGED' && !this._ended
        ? html`<div class="banner converged">Converged</div>` : nothing}

      ${this._options.map(o => html`
        <div class="card ${o.status.toLowerCase()}">
          <div class="card-header">
            <span class="card-title">${o.title}</span>
            <span class="status-badge ${o.status.toLowerCase()}">${o.status}</span>
          </div>
          <div class="card-body">
            <p>${o.description}</p>
          </div>
          ${o.tradeoffs ? html`<div class="tradeoffs">${o.tradeoffs}</div>` : nothing}
          ${this._isActionable(o.status) ? html`
            <div class="actions">
              ${o.status !== 'RECOMMENDED' ? html`
                <button @click=${() => this._patchStatus(o.id, 'RECOMMENDED')}>Recommend</button>
              ` : nothing}
              <button @click=${() => this._patchStatus(o.id, 'ELIMINATED')}>Eliminate</button>
              <button @click=${() => this._patchStatus(o.id, 'SELECTED')}>Select</button>
            </div>
          ` : nothing}
        </div>
      `)}

      ${this._errorMessage ? html`<div class="error-flash">${this._errorMessage}</div>` : nothing}
    `;
  }
}
