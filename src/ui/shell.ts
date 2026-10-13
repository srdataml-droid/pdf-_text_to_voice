import { icon } from './icons';

function navigation(): string {
  return `<button class="nav-button" type="button" data-view="library" aria-current="page">${icon('library')}<span>Library</span></button>
    <button class="nav-button reader-nav" type="button" data-view="reader">${icon('book-open')}<span>Reading</span></button>
    <button class="nav-button" type="button" data-view="models">${icon('audio-lines')}<span class="desktop-label">Models &amp; voices</span><span class="mobile-label">Voices</span></button>
    <button class="nav-button" type="button" data-view="storage">${icon('hard-drive')}<span class="desktop-label">Local storage</span><span class="mobile-label">Storage</span></button>`;
}

function addBook(): string {
  return `<button class="button button-primary" type="button" data-action="add-book">${icon('plus')}<span>Add a book</span></button>`;
}

function closeDialog(): string {
  return '<button class="icon-button dialog-close" type="button" data-action="close-dialog" aria-label="Close dialog">×</button>';
}

export function renderShell(): string {
  return `<div class="app-shell">
    <aside class="desktop-sidebar" aria-label="Reader navigation">
      <a class="brand" href="#" data-view="library"><span>Novaxis</span><small>READER · OFFLINE</small></a>
      <nav class="main-navigation" aria-label="Main">${navigation()}</nav>
      <div class="privacy-note">${icon('shield-check')}<p>Your books and voices stay on this device.</p><small>No account. No subscription.<br>No connection needed.</small></div>
    </aside>
    <main class="workspace" id="main-content">
      <section id="view-library" class="view library-view" aria-labelledby="library-heading">
        <header class="workspace-header"><div><h1 id="library-heading">Your library</h1><p>A quiet place for your books.</p></div>${addBook()}</header>
        <div class="view-content library-content">
          <section id="continue-reading" aria-label="Continue reading" hidden></section>
          <div class="library-toolbar">
            <label class="search-field">${icon('search')}<span class="visually-hidden">Search books</span><input id="library-search" type="search" placeholder="Search titles or authors" autocomplete="off"><kbd aria-hidden="true">Ctrl K</kbd></label>
            <label class="select-field">${icon('arrow-down-wide-narrow')}<span class="visually-hidden">Sort library</span><select id="library-sort"><option value="recent">Recently opened</option><option value="title">Title, A to Z</option><option value="added">Recently added</option></select></label>
            <label class="select-field">${icon('chevron-down')}<span class="visually-hidden">Filter format</span><select id="library-filter"><option value="all">All formats</option><option value="pdf">PDF</option><option value="epub">EPUB</option><option value="txt">TXT</option></select></label>
          </div>
          <div class="section-heading"><h2>On your bookshelf</h2><span id="book-count">0 books</span></div>
          <div id="library-list" class="book-grid library-list"></div>
          <div id="library-empty" class="empty-state">${icon('book-open', 40)}<h2>Your next chapter starts here.</h2><p>Choose a PDF, EPUB, or text book from your device.</p>${addBook()}<div class="format-tags"><span>PDF</span><span>EPUB</span><span>TXT</span></div></div>
          <p class="library-footnote">Imported books are copied to this device. Each device keeps its own library and reading position.</p>
        </div>
      </section>
      <section id="view-reader" class="view reader-view" aria-labelledby="book-title" hidden>
        <header class="workspace-header reader-header"><div class="reader-title-group"><div class="eyebrow" id="book-format">BOOK</div><h1 id="book-title">Your book</h1></div><div class="reader-actions"><button id="chapters-toggle" class="button button-quiet" type="button">Contents</button><button id="preferences-toggle" class="button button-quiet" type="button">Aa <span class="desktop-label">Appearance</span></button><button id="follow-voice" class="button button-secondary" type="button" aria-pressed="true">${icon('headphones')}<span>Follow voice</span></button></div></header>
        <div class="view-content reader-content"><div id="reader-status" class="reader-status" role="status" aria-live="polite"></div><article id="book-text" class="book-text" aria-label="Book content"></article></div>
      </section>
      <section id="view-models" class="view models-view" aria-labelledby="models-heading" hidden>
        <header class="workspace-header"><div><h1 id="models-heading">Models &amp; voices</h1><p>Choose the voice that makes a book feel yours.</p></div><span class="local-badge">${icon('shield-check')}Runs on this device</span></header>
        <div class="view-content">
          <select id="model" hidden aria-hidden="true" tabindex="-1"></select>
          <section id="model-stage"><div class="section-heading"><h2>Choose a model</h2></div><p class="section-description">Every model works locally. Pick one, then hear its voices.</p><div id="model-cards" class="model-grid"></div><p class="quiet-note">Only the model you use is loaded into memory.</p></section>
          <section id="voice-stage" hidden><button id="voices-back" class="button button-quiet back-button" type="button">← All models</button><div class="section-heading"><h2 id="voice-title">Choose a voice</h2></div><select id="voice" hidden aria-hidden="true" tabindex="-1"></select><div id="voice-list" class="voice-grid" role="group" aria-label="Available voices"></div><div class="voice-preview-card"><div><h3>Hear it before you choose.</h3><p>Each voice reads the same short passage, entirely on your device.</p></div><div class="voice-preview-actions"><button id="try-voice" class="button button-secondary" type="button">${icon('headphones')}Preview voice</button><button id="use-voice" class="button button-primary" type="button">Use this voice</button></div></div></section>
        </div>
      </section>
      <section id="view-storage" class="view storage-view" aria-labelledby="storage-heading" hidden>
        <header class="workspace-header"><div><h1 id="storage-heading">Local storage</h1><p>Your library, your voices, your device.</p></div><button id="add-piper-voice" class="button button-secondary" type="button">${icon('plus')}Import a Piper voice</button></header>
        <div class="view-content storage-content"><div id="storage-summary" class="storage-summary"></div><section class="storage-section"><div class="section-heading"><h2>Books on this device</h2><button class="button button-quiet" type="button" data-view="library">Open library →</button></div><div id="stored-books" class="storage-list"></div></section><section class="storage-section"><div class="section-heading"><h2>Voice packs</h2></div><p class="section-description">Bundled voices work offline. You can add a matching Piper model and configuration from your files.</p><div id="stored-voices" class="storage-list"></div></section><section class="backup-card">${icon('shield-check', 32)}<div><h2>Keep a copy of your library.</h2><p>Back up books and saved reading positions. Restore your copy on another device. Voice packs stay separate.</p></div><div class="backup-actions"><button id="export-library" class="button button-primary" type="button">Back up library</button><label id="import-backup-label" class="button button-secondary import-label" for="import-backup" tabindex="0" role="button">Restore backup</label></div></section></div>
      </section>
    </main>
    <nav class="mobile-navigation" aria-label="Mobile navigation">${navigation()}</nav>
    <footer id="player" class="player" aria-label="Reading player" hidden>
      <button id="player-expand" class="player-book" type="button" aria-label="Expand reading player" aria-expanded="false">${icon('book-open')}<span><strong id="player-title">Your book</strong><small id="location" aria-live="polite">Ready</small></span><span class="player-expand-mark" aria-hidden="true">⌃</span></button>
      <div class="player-transport"><button id="previous" class="icon-button transport" type="button" aria-label="Previous passage">‹‹</button><button id="play" class="play-button" type="button" aria-label="Play">▶</button><button id="next" class="icon-button transport" type="button" aria-label="Next passage">››</button></div>
      <div class="player-extra"><div class="progress-area"><label class="visually-hidden" for="scrub">Reading position</label><input id="scrub" class="scrub" type="range" min="0" max="0" value="0"><span id="segment-count" class="progress-meta">Ready</span></div><label class="speed-control" for="speed"><span>Speed</span><input id="speed" type="range" min="0.7" max="2" step="0.05" value="1"><b id="speed-value">1.00×</b></label><button class="button button-quiet player-voice-button" type="button" data-view="models">${icon('audio-lines')}<span>Voice</span></button></div>
      <audio id="audio" preload="auto"></audio>
    </footer>
    <input id="file-picker" type="file" accept=".pdf,.epub,.txt,application/pdf,application/epub+zip,text/plain" hidden>
    <input id="import-backup" type="file" accept=".zip,application/zip" hidden>
    <input id="voice-pack-picker" type="file" accept=".onnx,.json" multiple hidden>
    <div id="toast" class="toast" role="status" aria-live="polite"></div>
    <dialog id="preferences" class="reader-dialog" aria-labelledby="preferences-heading"><div class="dialog-heading"><h2 id="preferences-heading">Make yourself comfortable.</h2>${closeDialog()}</div><p class="dialog-description">Adjust your EPUB and text reading space.</p><fieldset class="preference-group"><legend>Reading theme</legend><div class="theme-buttons"><button type="button" class="theme-button" data-theme="paper" aria-pressed="true"><span class="theme-swatch paper-swatch">Aa</span>Paper</button><button type="button" class="theme-button" data-theme="dark" aria-pressed="false"><span class="theme-swatch dark-swatch">Aa</span>Dark</button></div></fieldset><label class="preference-field" for="reading-font"><span>Text size</span><input id="reading-font" type="range" min="16" max="32" step="1" value="22"></label><label class="preference-field" for="reading-spacing"><span>Line spacing</span><select id="reading-spacing"><option value="1.5">Compact</option><option value="1.75" selected>Comfortable</option><option value="2">Spacious</option></select></label><label class="preference-field" for="reading-width"><span>Reading width</span><select id="reading-width"><option value="narrow" selected>Focused</option><option value="wide">Wide</option></select></label><button class="button button-primary dialog-done" type="button" data-action="close-dialog">Done</button></dialog>
    <dialog id="chapters" class="reader-dialog" aria-labelledby="chapters-heading"><div class="dialog-heading"><h2 id="chapters-heading">Contents</h2>${closeDialog()}</div><div id="chapter-list" class="chapter-list"></div></dialog>
    <dialog id="recovery" class="reader-dialog" aria-labelledby="recovery-title"><div class="dialog-heading"><h2 id="recovery-title">Let’s get you reading.</h2>${closeDialog()}</div><p id="recovery-message" class="dialog-description"></p><div id="recovery-actions" class="dialog-actions"></div></dialog>
    <dialog id="book-ready" class="reader-dialog" aria-labelledby="ready-title"><div class="dialog-heading"><h2 id="ready-title">Your book is ready.</h2>${closeDialog()}</div><p id="ready-detail" class="dialog-description"></p><button id="ready-open" class="button button-primary dialog-done" type="button">${icon('book-open')}Open book</button></dialog>
  </div>`;
}
