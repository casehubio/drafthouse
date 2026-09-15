import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface NoteEntry { filename: string; title: string; date: string; tags: string[]; }

@customElement('note-list')
export class NoteList extends LitElement {
  @state() private notes: NoteEntry[] = [];
  @state() private filter = '';
  private _cleanups: (() => void)[] = [];

  static styles = css`
    :host { display: block; padding: 8px; overflow-y: auto; }
    .note-item {
      padding: 8px; margin: 4px 0; border-radius: 4px; cursor: pointer;
      border: 1px solid var(--border, #333);
    }
    .note-item:hover { background: var(--hover, rgba(255,255,255,0.05)); }
    .note-title { font-weight: 600; font-size: 14px; }
    .note-date { font-size: 11px; color: var(--text-secondary, #aaa); }
    .note-tags { font-size: 11px; color: var(--accent, #64b5f6); }
    input { width: 100%; padding: 6px; margin-bottom: 8px; box-sizing: border-box;
      background: var(--input-bg, #1e1e1e); color: var(--text, #ddd);
      border: 1px solid var(--border, #333); border-radius: 4px; }
    .empty { color: var(--text-secondary, #aaa); font-style: italic; padding: 12px; }
  `;

  connectedCallback() {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent(document, 'note-created', (payload: any) => {
        this.notes = [...this.notes, {
          filename: payload.path?.split('/').pop() || '',
          title: payload.title || 'Untitled',
          date: new Date().toISOString().slice(0, 10),
          tags: [],
        }];
      }),
      onPagesEvent(document, 'note-updated', () => this.requestUpdate())
    );
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private selectNote(note: NoteEntry) {
    this.dispatchEvent(new CustomEvent('note-selected', {
      detail: note, bubbles: true, composed: true,
    }));
  }

  render() {
    const filtered = this.notes.filter(n =>
      !this.filter || n.title.toLowerCase().includes(this.filter.toLowerCase()));
    return html`
      <input type="text" placeholder="Search notes..."
             .value=${this.filter} @input=${(e: any) => this.filter = e.target.value}>
      ${filtered.length === 0
        ? html`<div class="empty">No notes yet.</div>`
        : filtered.map(n => html`
          <div class="note-item" @click=${() => this.selectNote(n)}>
            <div class="note-title">${n.title}</div>
            <div class="note-date">${n.date}</div>
            ${n.tags.length ? html`<div class="note-tags">${n.tags.join(', ')}</div>` : ''}
          </div>
        `)}
    `;
  }
}
