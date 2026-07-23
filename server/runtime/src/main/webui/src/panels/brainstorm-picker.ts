import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface BrainstormSessionInfo {
  sessionId: string;
  state: string;
  optionCount: string;
}

@customElement('brainstorm-picker')
export class BrainstormPicker extends LitElement {
  @state() private _sessions: BrainstormSessionInfo[] = [];
  @state() private _currentSessionId: string | null = null;
  @state() private _open = false;

  private _cleanups: (() => void)[] = [];

  configure(_props: Record<string, unknown>): void {}

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<BrainstormSessionInfo[]>(document, 'brainstorm-sessions', (payload) => {
        this._sessions = payload;
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-session-created', (payload) => {
        if (!this._sessions.some(s => s.sessionId === payload.sessionId)) {
          this._sessions = [...this._sessions,
            { sessionId: payload.sessionId, state: 'ACTIVE', optionCount: '0' }];
        }
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-ended', (payload) => {
        this._sessions = this._sessions.filter(s => s.sessionId !== payload.sessionId);
        if (this._currentSessionId === payload.sessionId) {
          this._currentSessionId = null;
        }
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._sessions = [];
        this._currentSessionId = null;
      }),
    );

    const onOutsideClick = (e: MouseEvent) => {
      if (!this.contains(e.target as Node)) this._open = false;
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') this._open = false;
    };
    document.addEventListener('click', onOutsideClick);
    document.addEventListener('keydown', onEscape);
    this._cleanups.push(
      () => document.removeEventListener('click', onOutsideClick),
      () => document.removeEventListener('keydown', onEscape),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private _selectSession(sessionId: string, e: Event): void {
    e.stopPropagation();
    this._currentSessionId = sessionId;
    this._open = false;
    this.dispatchEvent(new CustomEvent('brainstorm-session-selected', {
      bubbles: true, composed: true,
      detail: { sessionId },
    }));
  }

  private _toggleOpen(e: Event): void {
    e.stopPropagation();
    this._open = !this._open;
  }

  private _shortId(id: string): string {
    return id.length > 10 ? id.substring(0, 10) + '…' : id;
  }

  static override styles = css`
    :host {
      display: inline-flex;
      position: relative;
      align-items: center;
      font-size: 12px;
    }

    .badge {
      cursor: pointer;
      user-select: none;
      padding: 2px 6px;
      border-radius: 3px;
    }
    .badge:hover { background: var(--accent-light); }

    .dropdown {
      position: absolute;
      top: 100%;
      left: 0;
      margin-top: 4px;
      min-width: 220px;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 4px;
      box-shadow: 0 4px 12px rgba(0,0,0,0.15);
      z-index: 1000;
      padding: 8px 0;
    }

    .header {
      padding: 4px 12px 8px;
      font-weight: 600;
      font-size: 11px;
      color: var(--muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .session-row {
      display: flex;
      align-items: center;
      padding: 4px 12px;
      gap: 6px;
      cursor: pointer;
    }
    .session-row:hover { background: var(--chrome); }
    .session-row.active { background: var(--accent-light); }

    .session-id {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
    }

    .option-count {
      font-size: 11px;
      color: var(--muted);
    }
  `;

  override render() {
    if (this._sessions.length === 0) return nothing;

    return html`
      <span class="badge" @click=${this._toggleOpen}>
        Sessions (${this._sessions.length})
      </span>
      ${this._open ? html`
        <div class="dropdown">
          <div class="header">Brainstorm Sessions</div>
          ${this._sessions.map(s => html`
            <div class="session-row ${s.sessionId === this._currentSessionId ? 'active' : ''}"
                 @click=${(e: Event) => this._selectSession(s.sessionId, e)}>
              <span class="session-id">${this._shortId(s.sessionId)}</span>
              <span class="option-count">${s.optionCount} opts</span>
            </div>
          `)}
        </div>
      ` : nothing}
    `;
  }
}
