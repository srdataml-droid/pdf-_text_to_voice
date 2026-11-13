type Folder = { id: string; name: string; books: string[] };
type Note = { id: string; book: string; position: number; quote: string; lesson: string };
type Saved = { folders: Folder[]; notes: Note[] };
const KEY = 'worthkind-reader-organizer-v1';
export class Organizer {
  private data: Saved;
  private selected = '';
  constructor(private changed: () => void, private current: () => {id:string; position:number} | undefined, private jump: (position:number) => void) {
    const raw = localStorage.getItem(KEY);
    this.data = raw ? JSON.parse(raw) as Saved : {folders:['Health','Personal Development','Manhwa'].map(name=>({id:crypto.randomUUID(),name,books:[]})),notes:[]};
  }
  private save(next: Saved): void { localStorage.setItem(KEY,JSON.stringify(next)); this.data=next; }
  includes(id:string):boolean { return !this.selected || !!this.data.folders.find(f=>f.id===this.selected)?.books.includes(id); }
  private button(label:string,run:()=>void):HTMLButtonElement {const b=document.createElement('button');b.type='button';b.className='button button-secondary';b.textContent=label;b.onclick=()=>{try{run();}catch(e){alert('Could not save: '+String(e));}};return b;}
  mount():void {
    const bar=document.createElement('section');bar.className='organizer-bar';bar.setAttribute('aria-label','Book folders');
    const select=document.createElement('select');select.setAttribute('aria-label','Folder');
    const render=()=>{select.replaceChildren(new Option('All books',''));for(const f of this.data.folders)select.add(new Option(f.name,f.id));select.value=this.selected;};render();
    select.onchange=()=>{this.selected=select.value;this.changed();};bar.append(select,this.button('Create folder',()=>{const name=prompt('Folder name')?.trim();if(!name)return;if(this.data.folders.some(f=>f.name.toLowerCase()===name.toLowerCase()))throw Error('Folder already exists.');this.save({...this.data,folders:[...this.data.folders,{id:crypto.randomUUID(),name,books:[]}]});render();}),this.button('Rename folder',()=>{const f=this.data.folders.find(f=>f.id===this.selected);if(!f)return alert('Choose a folder.');const name=prompt('Folder name',f.name)?.trim();if(name){this.save({...this.data,folders:this.data.folders.map(x=>x.id===f.id?{...x,name}:x)});render();}}));
    document.querySelector('#library-list')!.before(bar);
    let quote='';let selectedPosition:number|undefined;const add=this.button('Highlight & note',()=>{const current=this.current();if(!current)return;if(!quote)return alert('Select text in the book first. Image-only pages do not have selectable text.');const lesson=prompt('What did you learn?');if(lesson===null)return;this.save({...this.data,notes:[...this.data.notes,{id:crypto.randomUUID(),book:current.id,position:selectedPosition??current.position,quote,lesson}]});this.restoreHighlights();alert('Highlight and learning note saved. Open Notes & highlights to revisit it.');});
    add.onpointerdown=e=>{const s=getSelection(),host=document.querySelector('#book-text');quote=s&&host?.contains(s.anchorNode)&&host.contains(s.focusNode)?s.toString().trim():'';const parent=s?.anchorNode?.parentElement?.closest<HTMLElement>('[data-index]');selectedPosition=parent?.dataset.index===undefined?undefined:Number(parent.dataset.index);e.preventDefault();};
    document.querySelector('.reader-actions')!.append(add,this.button('Notes & highlights',()=>this.showNotes()));
  }
  bookControl(card:HTMLElement,id:string):void {
    card.append(this.button('Folders',()=>{const dialog=document.createElement('dialog');dialog.className='reader-dialog';const heading=document.createElement('h2');heading.textContent='Choose book folders';dialog.append(heading);
      const choices=this.data.folders.map(f=>{const label=document.createElement('label');label.style.display='block';const check=document.createElement('input');check.type='checkbox';check.checked=f.books.includes(id);label.append(check,document.createTextNode(' '+f.name));dialog.append(label);return {f,check};});
      const close=()=>{dialog.close();dialog.remove();};dialog.append(this.button('Save',()=>{this.save({...this.data,folders:choices.map(({f,check})=>({...f,books:check.checked?[...new Set([...f.books,id])]:f.books.filter(x=>x!==id)}))});close();this.changed();}),this.button('Cancel',close));document.body.append(dialog);dialog.showModal();}));
  }
  restoreHighlights():void {
    const current=this.current(),root=document.querySelector('#book-text');
    const api=CSS as unknown as {highlights?:Map<string,unknown>};
    const Highlight=(window as unknown as {Highlight?:new (...ranges:Range[])=>unknown}).Highlight;
    if(!root||!current||!Highlight||!api.highlights)return;
    const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),nodes:Text[]=[];let value='',next:Node|null;
    while((next=walker.nextNode())){nodes.push(next as Text);value+=next.textContent??'';}
    const ranges:Range[]=[];
    for(const note of this.data.notes.filter(n=>n.book===current.id)){
      const start=value.indexOf(note.quote);if(start<0)continue;let offset=0;const range=document.createRange();
      for(const node of nodes){const end=offset+node.length;if(start>=offset&&start<end)range.setStart(node,start-offset);if(start+note.quote.length>offset&&start+note.quote.length<=end){range.setEnd(node,start+note.quote.length-offset);ranges.push(range);break;}offset=end;}
    }
    api.highlights.set('learning-notes',new Highlight(...ranges));
  }
  private showNotes():void {
    const current=this.current();if(!current)return;const dialog=document.createElement('dialog');dialog.className='reader-dialog';const title=document.createElement('h2');title.textContent='Notes & highlights';dialog.append(title);
    const notes=this.data.notes.filter(n=>n.book===current.id);if(!notes.length)dialog.append(document.createTextNode('Select text in your book, then choose Highlight & note.'));
    for(const n of notes){const row=document.createElement('section'),quote=document.createElement('blockquote'),lesson=document.createElement('p');quote.textContent=n.quote;lesson.textContent=n.lesson;row.append(quote,lesson,this.button('Return to passage',()=>{dialog.close();dialog.remove();this.jump(n.position);setTimeout(()=>this.restoreHighlights(),500);}),this.button('Edit',()=>{const value=prompt('What did you learn?',n.lesson);if(value!==null){this.save({...this.data,notes:this.data.notes.map(x=>x.id===n.id?{...x,lesson:value}:x)});lesson.textContent=value;n.lesson=value;}}),this.button('Delete',()=>{if(confirm('Delete this highlight and note?')){this.save({...this.data,notes:this.data.notes.filter(x=>x.id!==n.id)});row.remove();}}));dialog.append(row);}
    dialog.append(this.button('Export notes',()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(notes,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='reading-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}),this.button('Close',()=>{dialog.close();dialog.remove();}));document.body.append(dialog);dialog.showModal();
  }
}
