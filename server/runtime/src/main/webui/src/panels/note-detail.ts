import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

@customElement('note-detail')
export class NoteDetail extends LitElement {
  @state() private title = '';
  @state() private content = '';
  @state() private showFrontmatter = false;
  @state() private frontmatter = '';
  private _cleanups: (() => void)[] = [];

  static styles = css`
    :host { display: block; padding: 12px; overflow-y: auto; }
    h2 { margin: 0 0 8px; font-size: 16px; }
    .content { white-space: pre-wrap; line-height: 1.6; }
    .toggle { font-size: 11px; cursor: pointer; color: var(--accent, #64b5f6);
      border: none; background: none; padding: 0; margin-bottom: 8px; }
    .frontmatter { font-size: 12px; background: var(--code-bg, #1e1e1e);
      padding: 8px; border-radius: 4px; margin-bottom: 8px; white-space: pre; font-family: monospace; }
    .empty { color: var(--text-secondary, #aaa); font-style: italic; }
  `;

  connectedCallback() {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent(document, 'note-created', (payload: any) => {
        this.title = payload.title || 'Untitled';
        this.content = payload.summary || '';
      })
    );
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  configure(props: { title?: string; content?: string; frontmatter?: string }) {
    if (props.title) this.title = props.title;
    if (props.content) this.content = props.content;
    if (props.frontmatter) this.frontmatter = props.frontmatter;
  }

  render() {
    if (!this.title && !this.content) {
      return html`<div class="empty">Select a note to view.</div>`;
    }
    return html`
      <h2>${this.title}</h2>
      ${this.frontmatter ? html`
        <button class="toggle" @click=${() => this.showFrontmatter = !this.showFrontmatter}>
          ${this.showFrontmatter ? 'Hide' : 'Show'} metadata
        </button>
        ${this.showFrontmatter ? html`<pre class="frontmatter">${this.frontmatter}</pre>` : ''}
      ` : ''}
      <div class="content">${this.content}</div>
    `;
  }
}
