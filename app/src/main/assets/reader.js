import './foliate/view.js'
let view, info, quietUntil=0, selected=-1, userUntil=0, appearance={}, styleTimer, turning=false, highlightActive=false
let preview=null, deferredGo=null, deferredStyle=null
const painted=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))
// Smooth following uses Foliate's scrolled flow, which must reproduce the paged layout exactly:
// the same column width (so every line breaks in the same place), the same left edge, and the
// same top spacing. The paged column width is read from the layout itself, not estimated.
let paged=null  // {left, width} in viewport CSS px, from the paged flow
let kept=null  // {range,text,y}: the text line at the middle of the screen when the flow switches, landed back in place
let lastEngage=null  // {before,after}: that line before and after the switch (test support)
// Foliate re-anchors the view whenever the chapter document or its container resizes, fonts finish
// loading, or a layout attribute changes, by writing the container's scroll position; its anchor is
// often the sentence it last navigated to, so in the smooth-follow flow that snaps the text about.
// While following, the reader owns the scroll position: Foliate's writes are swallowed, except
// around a deliberate move into another chapter. Every scroll the reader makes is logged, so a
// test can tell a follow from a jump.
const scrollTopProp=Object.getOwnPropertyDescriptor(Element.prototype,'scrollTop')
let held=null,allowAnchor=false,scrollSeq=0,lastScroll=null
function holdScroll(container){
    if(!container||held===container)return
    releaseScroll()
    Object.defineProperty(container,'scrollTop',{configurable:true,get(){return scrollTopProp.get.call(this)},set(v){if(allowAnchor)scrollTopProp.set.call(this,v)}})
    held=container
}
function releaseScroll(){if(held){delete held.scrollTop;held=null}}
function moveTo(container,top,smooth,kind){
    const max=Math.max(0,container.scrollHeight-container.clientHeight)
    const to=Math.max(0,Math.min(top,max)),from=container.scrollTop
    lastScroll={seq:++scrollSeq,kind,from:Math.round(from*10)/10,to:Math.round(to*10)/10}
    container.scrollTo({top:to,behavior:smooth?'smooth':'instant'})
}
const moveBy=(container,delta,smooth,kind)=>moveTo(container,container.scrollTop+delta,smooth,kind)
// The text line nearest the middle of the screen: its range (valid across a flow change of the same
// document), its text, and its top in viewport px.
function centerLine(){
    try{
        const c=view.renderer.getContents()[0];if(!c)return null
        const doc=c.doc,container=scrollContainer(doc),fr=doc.defaultView.frameElement.getBoundingClientRect(),vw=container.getBoundingClientRect()
        for(const f of [0.5,0.42,0.58,0.34,0.66,0.26,0.74,0.18,0.82]){
            const caret=doc.caretRangeFromPoint(vw.left+vw.width/2-fr.left,vw.top+vw.height*f-fr.top)
            if(!caret||caret.startContainer.nodeType!==3)continue
            const n=caret.startContainer,o=caret.startOffset
            const r=doc.createRange();r.setStart(n,Math.max(0,o-1));r.setEnd(n,Math.min(n.length,Math.max(o,1)))
            const rect=[...r.getClientRects()].find(x=>x.height>0);if(!rect)continue
            return {range:r,text:n.data.slice(Math.max(0,o-24),o+24),y:Math.round((fr.top+rect.top)*10)/10}
        }
    }catch(e){}
    return null
}
function landKept(){
    const k=kept;kept=null;if(!k)return
    try{
        const c=view.renderer.getContents()[0],container=scrollContainer(c.doc),fr=c.doc.defaultView.frameElement.getBoundingClientRect()
        const rect=[...k.range.getClientRects()].find(x=>x.height>0)
        if(container&&rect)moveBy(container,fr.top+rect.top-k.y,false,'land')
    }catch(e){}
}
window.readerEngage=()=>JSON.stringify(lastEngage)
// One cheap sample of what is on screen: flow, scroll position, the text line at the middle and its
// top, the highlighted sentence (or -1), and the reader's latest scroll (so movement can be explained).
window.readerProbe=()=>{try{
    const c=view.renderer.getContents()[0],container=scrollContainer(c.doc),line=centerLine()
    const n=c.doc.defaultView.CSS?.highlights?.get('narration')?.size||0
    return JSON.stringify({f:view.renderer.getAttribute('flow'),t:Math.round(container.scrollTop*10)/10,l:Math.round(container.scrollLeft),c:line?line.text:null,y:line?line.y:null,h:n>0?selected:-1,s:lastScroll})
}catch(e){return 'null'}}
function pagedGeometry(detail){
    try{
        const doc=detail.range.startContainer.ownerDocument
        if(!doc.documentElement.style.columnWidth)return
        // The page's single column fills the document's content box exactly (fractional px), so
        // take the real content width and left padding from the computed style, not from ink.
        const cs=getComputedStyle(doc.documentElement)
        const pl=parseFloat(cs.paddingLeft),pr=parseFloat(cs.paddingRight)
        const width=parseFloat(cs.width)-pl-pr
        if(!(width>0))return
        // The column height (where the page grid breaks) and the body's own top margin, for the grid shift.
        const height=doc.documentElement.clientHeight-(parseFloat(cs.paddingTop)||0)-(parseFloat(cs.paddingBottom)||0)
        const bodyMargin=parseFloat(getComputedStyle(doc.body).marginTop)||0
        paged={left:pl,width,height,bodyMargin}
    }catch(e){}
}
function scrollContainer(doc){return doc.defaultView.frameElement?.parentElement?.parentElement||null}
// Undo every scrolled-flow override so Foliate's paged layout is exactly what it was.
function clearScrolledOverrides(){
    const r=view?.renderer;if(!r)return
    for(const {doc} of r.getContents()){
        const frame=doc.defaultView.frameElement;const container=scrollContainer(doc);if(!frame)continue
        container?.style.removeProperty('grid-column');container?.style.removeProperty('width')
        // Foliate's scrolled layout zeroes the outer container's padding and does not restore it.
        container?.parentElement?.style.removeProperty('padding')
        frame.parentElement?.style.removeProperty('width');frame.style.removeProperty('width')
        doc.documentElement.style.removeProperty('padding')
        doc.body.style.removeProperty('max-width');doc.body.style.removeProperty('width');doc.body.style.removeProperty('margin')
    }
}
function alignScrolled(){
    const r=view?.renderer;if(!r||r.getAttribute('flow')!=='scrolled'||!paged)return
    for(const {doc} of r.getContents()){
        const frame=doc.defaultView.frameElement;const container=scrollContainer(doc);if(!frame||!container)continue
        // Foliate keeps two side columns in its grid even for the scrolled flow; span them all.
        container.style.setProperty('grid-column','1 / -1','important');container.style.setProperty('width',`${innerWidth}px`,'important')
        frame.parentElement.style.setProperty('width','100%','important');frame.style.setProperty('width','100%','important')
        const fr=frame.getBoundingClientRect()
        const left=Math.max(0,paged.left-fr.left)
        const right=Math.max(0,fr.width-left-paged.width)
        doc.documentElement.style.setProperty('padding',`34px ${right}px 34px ${left}px`,'important')
        // The body gets the paged column's exact width so every line breaks where the page broke it.
        doc.body.style.setProperty('max-width','none','important')
        doc.body.style.setProperty('width',`${paged.width}px`,'important')
        doc.body.style.setProperty('margin','0','important')
        const h=doc.documentElement.getBoundingClientRect().height
        frame.style.height=`${h}px`;frame.parentElement.style.height=`${h}px`
    }
    r.expand?.()
}
// Keep the narrated sentence comfortably in view with a smooth scroll instead of a jump.
function smoothFollow(doc,range){
    const container=scrollContainer(doc);const fr=doc.defaultView.frameElement?.getBoundingClientRect();if(!container||!fr)return false
    const rect=range.getBoundingClientRect();if(!(rect.height>0))return false
    const top=fr.top+rect.top,bottom=fr.top+rect.bottom
    const view=container.getBoundingClientRect()
    const lower=view.top+view.height*0.72,upper=view.top+view.height*0.12
    if(bottom>lower||top<upper)moveBy(container,top-(view.top+view.height*0.3),true,'follow')
    return true
}
function marginals(detail){
    const r=view?.renderer;if(!r||!detail)return
    const chapter=(detail.tocItem?.label||'').trim()
    const head=chapter&&chapter.toLowerCase()!=='contents'?chapter:(info?.title||'')
    const foot=`${Math.round((detail.fraction||0)*100)}%   ·   ${info?.title||''}`
    for(const el of r.heads||[])el.textContent=head
    for(const el of r.feet||[])el.textContent=foot
}
function publishLocation(detail,select){
    if(!detail)return
    marginals(detail)
    if(info?.cacheOnly)return
    if(view?.renderer?.getAttribute('flow')==='scrolled')alignScrolled()
    else if(detail.range)pagedGeometry(detail)
    ReaderBridge.location(detail.tocItem?.label||'',detail.fraction||0)
    ReaderBridge.pageChanged(detail.cfi||'')
    if(!select||!detail.range)return
    const range=detail.range,doc=range.startContainer.ownerDocument
    const index=view.renderer.getContents().find(c=>c.doc===doc)?.index
    const item=mapDocument(doc,index).ranges.find(r=>r.range.compareBoundaryPoints(Range.START_TO_END,range)>0)
    if(item){selected=item.id;ReaderBridge.select(item.id)}
}
// This runs only in the noninteractive sibling WebView. It never moves the live
// reader or publishes selections. Native capture acknowledges each painted page.
let cacheJob=null, cacheQueue=Promise.resolve()
window.readerCancelCache=()=>{if(cacheJob){cacheJob.cancelled=true;cacheJob.resume?.()}}
window.readerCacheCaptured=token=>{if(cacheJob?.token===token)cacheJob.resume?.()}
// The paged layout is settled when the chapter has its pages (a section being laid out again reports a
// single page for a moment) and two paints in a row agree on them. Stepping from an unsettled chapter
// lands in the next chapter instead of on the next page.
let cacheStyleKey=''
async function settled(){
    for(let i=0;i<45;i++){
        const r=view.renderer;if(r.getAttribute('flow')==='scrolled')return
        const pages=r.pages,size=r.viewSize
        await painted()
        if(r.pages>=3&&r.pages===pages&&r.viewSize===size)return
    }
}
window.readerCache=(cfi,token,offsets,style)=>{
    window.readerCancelCache()
    const job={token,cancelled:false,resume:null};cacheJob=job
    cacheQueue=cacheQueue.catch(()=>{}).then(async()=>{
        try{
            if(job.cancelled)return
            // Styling again lays the chapter out again; only a changed appearance needs it.
            const key=JSON.stringify(style);if(key!==cacheStyleKey){cacheStyleKey=key;window.readerStyle(style)}
            for(const offset of offsets){
                if(job.cancelled)return
                await view.goTo(cfi)
                for(const {doc} of view.renderer.getContents())await doc.fonts.ready
                await settled()
                // Font loading can repaginate the chapter: anchor again afterwards.
                await view.goTo(cfi)
                await settled()
                const base=view.lastLocation.cfi
                if(offset)await (offset>0?view.next():view.prev())
                for(const {doc} of view.renderer.getContents())await doc.fonts.ready
                await painted()
                if(job.cancelled)return
                const target=view.lastLocation.cfi
                await new Promise(resolve=>{
                    job.resume=resolve
                    ReaderBridge.cachePage(token,offset,offset&&target===base?'':target)
                })
                job.resume=null
            }
        }catch(e){ReaderBridge.error('Page cache: '+e)}
        finally{ReaderBridge.cacheDone(token)}
    })
}
// A preview may move the renderer, but cannot move the saved reading/audio cursor.
// The sentences at the screen's edges in the smooth-follow flow, measured from the layout itself:
// the first one visible, the last one wholly visible, and the first one wholly below the screen.
function screenEdges(){
    const content=view.renderer.getContents()[0];if(!content)return null
    const doc=content.doc,container=scrollContainer(doc),frame=doc.defaultView.frameElement;if(!container||!frame)return null
    const fr=frame.getBoundingClientRect(),vw=container.getBoundingClientRect()
    let first=null,seen=null,below=null
    for(const r of mapDocument(doc,content.index).ranges){
        const rects=[...r.range.getClientRects()].filter(x=>x.height>0);if(!rects.length)continue
        const top=fr.top+Math.min(...rects.map(x=>x.top)),bottom=fr.top+Math.max(...rects.map(x=>x.bottom))
        if(first===null&&bottom>vw.top+1)first=r
        if(top>=vw.top-1&&bottom<=vw.bottom+1)seen=r
        if(below===null&&top>=vw.bottom-1){below=r;break}
    }
    return {index:content.index,doc,fr,vw,first,seen,below}
}
window.readerEdges=()=>{try{const e=screenEdges();return JSON.stringify(e?{first:e.first?e.first.id:-1,seen:e.seen?e.seen.id:-1,below:e.below?e.below.id:-1}:null)}catch(e){return 'null'}}
let lastShift=null
window.readerShift=()=>JSON.stringify(lastShift)
// Pages can only begin where the column grid breaks. To continue exactly from the screen, the
// chapter's body is pushed down (extra top padding) until a break falls on the first line the reader
// has not seen; the only trace is some space at the top of the chapter's first page, until the book
// is opened again. Both renderers apply the shift from the style, so cached pages match live ones.
function applyGridShift(style,doc,index){
    const b=doc.body;if(!b)return
    if(!style.scroll&&style.shiftPx>0&&index===style.shiftIndex){
        if(b.dataset.basePad===undefined)b.dataset.basePad=getComputedStyle(b).paddingTop
        b.style.setProperty('padding-top',`calc(${b.dataset.basePad} + ${style.shiftPx}px)`,'important')
    }else if(b.dataset.basePad!==undefined){b.style.removeProperty('padding-top');delete b.dataset.basePad}
}
function applyGridShifts(style){for(const {doc,index} of view?.renderer?.getContents()||[])applyGridShift(style,doc,index)}
// Leaving the smooth-follow flow for pages underneath a turn: the screen the reader sees is the
// outgoing page, and the page that arrives begins at the first line below the screen (or, turning
// back, ends at the first line on it), so nothing is skipped and nothing is repeated. The edge line
// is found on the screen, then measured again on the real page after the flow changes, and the
// chapter's grid shift is corrected from that measurement rather than predicted. Returns the paged
// location of the screen itself, for a cancelled turn.
async function exitScroll(direction){
    if(view.renderer.getAttribute('flow')!=='scrolled')return null
    const content=view.renderer.getContents()[0]
    const doc=content?.doc,index=content?.index,container=doc&&scrollContainer(doc),frame=doc?.defaultView.frameElement
    let line=null,lineRange=null
    if(doc&&container&&frame){
        const fr=frame.getBoundingClientRect(),vw=container.getBoundingClientRect()
        // In the frame's coordinates. Forward: the first line not wholly on screen. Back: the first line wholly on screen.
        const top=vw.top-fr.top,bottom=vw.bottom-fr.top
        for(const r of mapDocument(doc,index).ranges)for(const x of r.range.getClientRects()){
            if(!(x.height>0))continue
            const ok=direction>0?x.bottom>bottom-1:x.top>=top-1
            if(ok&&(line===null||x.top<line.top))line=x
        }
        if(line){
            const caret=doc.caretRangeFromPoint(line.left+1,line.top+Math.min(2,line.height/2))
            if(caret){lineRange=caret.cloneRange();try{if(caret.startContainer.nodeType===3&&caret.startOffset<caret.startContainer.length)lineRange.setEnd(caret.startContainer,caret.startOffset+1)}catch(e){}}
        }
    }
    let shiftIndex=-1,shiftPx=0
    const styled=()=>Object.assign({},appearance,{scroll:false,shiftIndex,shiftPx})
    applyStyle(styled());clearTimeout(styleTimer)
    await painted()
    lastShift=null
    let homeCfi=view.lastLocation?.cfi,landed=false
    if(doc&&lineRange){
        const cs=getComputedStyle(doc.documentElement)
        const pt=parseFloat(cs.paddingTop)||0,pb=parseFloat(cs.paddingBottom)||0
        const H=frame.getBoundingClientRect().height-pt-pb
        const toLine=async()=>{await view.renderer.goTo({index,anchor:()=>lineRange});await painted()}
        // How far down its page the edge line sits, measured on the page itself.
        const offset=()=>{const r=lineRange.getClientRects()[0]||lineRange.getBoundingClientRect();return r.top-pt}
        const reshift=async px=>{shiftIndex=px>0?index:-1;shiftPx=px>0?Math.round(px*100)/100:0;applyGridShifts(styled());view.renderer.expand?.();await painted();await toLine()}
        await toLine()
        const first=offset(),lh=line.height
        let after=first
        // A break already within half a line of the edge needs no shift; one that would leave most
        // of the chapter's first page empty is not taken either (a few repeated lines then).
        if(H>0&&first>lh*0.5&&H-first<=H*0.85){
            // Padding on the body also stops its first child's margin collapsing through it, and the
            // glyph box sits a half-leading below its line box, so the shift is settled by measuring:
            // a line found a little way down its page pulls the shift back by that much, one found
            // near the bottom pushes it on to the next break, until the measurement stops changing.
            await reshift(H-first);after=offset()
            for(let i=0;i<4&&Math.abs(after)>1;i++){
                let px=after<H/2?shiftPx-after:shiftPx+(H-after);if(px<0)px+=H
                await reshift(px);const now=offset()
                if(Math.abs(now-after)<0.5)break
                after=now
            }
        }
        lastShift={H:Math.round(H*10)/10,first:Math.round(first),after:Math.round(after*10)/10,shift:shiftPx}
        appearance=styled()
        // The page that begins with the edge line, and the page before it. Forward: the screen is
        // the one before, and the turn shows the one beginning with the line. Back: the screen is
        // the one beginning with the line, and the turn shows the one before.
        const edgeCfi=view.lastLocation.cfi
        await view.prev();await painted()
        const prevCfi=view.lastLocation.cfi
        if(direction>0){homeCfi=prevCfi;await view.goTo(edgeCfi);await painted()}
        else homeCfi=edgeCfi
        landed=true
    }
    ReaderBridge.gridShift(shiftIndex,shiftPx)
    if(!landed)await (direction>0?view.next():view.prev())
    for(const {doc} of view.renderer.getContents())await doc.fonts.ready
    await painted()
    return homeCfi||view.lastLocation.cfi
}
window.readerPreview=(direction,token,target=null,exit=false)=>{
    if(preview||turning){ReaderBridge.previewReady(token,false);return}
    clearTimeout(styleTimer)
    const tx={token,cfi:view.lastLocation.cfi,selected,highlight:highlightActive,changed:false,target}
    preview=tx;turning=true;window.readerClear()
    tx.ready=(async()=>{
        try{
            if(exit){const home=await exitScroll(direction);if(home!==null){tx.cfi=home;tx.changed=view.lastLocation.cfi!==home;clearTimeout(styleTimer);return}}
            if(target){tx.changed=target!==tx.cfi;return}
            await (direction>0?view.next():view.prev())
            for(const {doc} of view.renderer.getContents())await doc.fonts.ready
            await painted();tx.changed=view.lastLocation.cfi!==tx.cfi
        }catch(e){ReaderBridge.error(String(e))}
        finally{ReaderBridge.previewReady(token,tx.changed)}
    })()
}
window.readerResolve=async(token,commit)=>{
    const tx=preview;if(!tx||tx.token!==token)return
    await tx.ready
    try{
        if(!commit||!tx.changed){
            if(!tx.target)await view.goTo(tx.cfi)
            selected=tx.selected;highlightActive=tx.highlight
            if(tx.highlight){for(const {doc,index} of view.renderer.getContents()){
                const item=mapDocument(doc,index).ranges.find(r=>r.id===selected);if(item)mark(doc,item.range)
            }}
        }else{
            if(tx.target)await view.goTo(tx.target)
            highlightActive=false
        }
        await painted()
    }finally{
        preview=null;turning=false;quietUntil=Date.now()+300;userUntil=0
        publishLocation(view.lastLocation,commit&&tx.changed)
        const go=deferredGo;deferredGo=null
        if(go&&!commit)await window.readerGo(...go)
        if(deferredStyle){const style=deferredStyle;deferredStyle=null;window.readerStyle(style)}
        ReaderBridge.previewResolved(token)
    }
}
const normalized=s=>s.replace(/\s+/g,' ').trim()
const maps=new WeakMap()
function mapDocument(doc,index){
    if(maps.has(doc))return maps.get(doc)
    const nodes=[],offsets=[];let text='',space=false
    const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT,{acceptNode:n=>n.parentElement.closest('script,style')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT})
    while(walker.nextNode()){const node=walker.currentNode;for(let i=0;i<node.length;i++){const char=node.data[i];if(/\s/.test(char)){if(space)continue;text+=' ';space=true}else{text+=char;space=false}nodes.push(node);offsets.push(i)}}
    const passages=info.kindle?info.passages:info.passages.filter(p=>p.section===index),ranges=[];let cursor=0
    for(const p of passages){const query=normalized(p.text);let start=text.indexOf(query,cursor);if(start<0)start=text.indexOf(query);if(start<0)continue;const end=start+query.length-1;const range=doc.createRange();range.setStart(nodes[start],offsets[start]);range.setEnd(nodes[end],offsets[end]+1);ranges.push({id:p.id,range,start,end});cursor=end+1}
    const map={ranges,nodes,offsets,text};maps.set(doc,map);return map
}
function mark(doc,range){if(doc.defaultView.CSS?.highlights)doc.defaultView.CSS.highlights.set('narration',new doc.defaultView.Highlight(range))}
window.readerGo=async (id,highlight=true)=>{
    if(preview){deferredGo=[id,highlight];return}
    highlightActive=highlight;selected=id;const p=info?.passages[id];if(!p||!view)return;quietUntil=Date.now()+1200
    if(highlight)window.readerClearSelection()
    let index=p.section
    if(info.kindle){for(let i=0;i<view.book.sections.length;i++){const doc=await view.book.sections[i].createDocument();if(mapDocument(doc,i).ranges.some(r=>r.id===id)){index=i;break}}}
    if(view.renderer.getAttribute('flow')==='scrolled'){try{
        const content=view.renderer.getContents().find(c=>c.index===index)
        const item=content&&mapDocument(content.doc,index).ranges.find(r=>r.id===id)
        if(item){if(highlight)mark(content.doc,item.range);else content.doc.defaultView.CSS?.highlights?.delete('narration')
            // Paused: nothing moves. Narrating: keep the sentence in view with a smooth scroll.
            if(!highlight)return
            if(smoothFollow(content.doc,item.range))return}
        if(!highlight)return
        // The sentence is in a chapter not on screen: move there (Foliate may scroll for this), align
        // the new chapter, place the sentence, and own the scroll again.
        allowAnchor=true
        try{
            await view.renderer.goTo({index,anchor:doc=>{const item=mapDocument(doc,index).ranges.find(r=>r.id===id);if(item){mark(doc,item.range);return item.range}return doc.body}})
            for(const {doc} of view.renderer.getContents())await doc.fonts.ready
            await painted();alignScrolled()
            const c=view.renderer.getContents().find(c=>c.index===index),it=c&&mapDocument(c.doc,index).ranges.find(r=>r.id===id)
            if(it){const container=scrollContainer(c.doc),fr=c.doc.defaultView.frameElement.getBoundingClientRect(),rect=it.range.getBoundingClientRect(),vw=container.getBoundingClientRect()
                if(rect.height>0)moveBy(container,fr.top+rect.top-(vw.top+vw.height*0.3),false,'chapter')}
            lastScroll={seq:++scrollSeq,kind:'chapter',from:null,to:null}
        }catch(e){}finally{allowAnchor=false}
        return
    }catch(e){}}
    // Pages: a sentence already on the page needs no move. One that straddles the page break begins
    // on the previous page, and asking Foliate for it would turn back to that page.
    try{
        const content=view.renderer.getContents().find(c=>c.index===index),loc=view.lastLocation?.range
        const item=content&&loc&&loc.startContainer.ownerDocument===content.doc&&mapDocument(content.doc,index).ranges.find(r=>r.id===id)
        if(item&&item.range.compareBoundaryPoints(Range.START_TO_END,loc)>0&&item.range.compareBoundaryPoints(Range.END_TO_START,loc)<0){
            if(highlight)mark(content.doc,item.range);else content.doc.defaultView.CSS?.highlights?.delete('narration')
            return
        }
    }catch(e){}
    await view.renderer.goTo({index,anchor:doc=>{const item=mapDocument(doc,index).ranges.find(r=>r.id===id);if(item){if(highlight)mark(doc,item.range);else doc.defaultView.CSS?.highlights?.delete('narration');return item.range}return doc.body}})
}
window.readerStep=async (direction,exit=false)=>{
    if(turning){ReaderBridge.turned();return}
    turning=true;quietUntil=0;userUntil=Date.now()+5000;window.readerClear();highlightActive=false
    try{let done=false
        if(exit){done=(await exitScroll(direction))!==null;clearTimeout(styleTimer)}
        if(!done)await (direction>0?view.next():view.prev())
        await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))}
    finally{turning=false;ReaderBridge.turned()}
}
// First and last sentence ids on the visible page, as a JSON pair; -1 when unknown.
window.readerVisible=()=>{try{
    const d=view?.lastLocation;if(!d?.range)return '[-1,-1]'
    const doc=d.range.startContainer.ownerDocument
    const index=view.renderer.getContents().find(c=>c.doc===doc)?.index
    const ranges=mapDocument(doc,index).ranges
    // compareBoundaryPoints(START_TO_END): this.end vs page.start; (END_TO_START): this.start vs page.end.
    const at=ranges.findIndex(r=>r.range.compareBoundaryPoints(Range.START_TO_END,d.range)>0)
    if(at<0)return '[-1,-1]'
    let last=at
    for(let i=at+1;i<ranges.length;i++){if(ranges[i].range.compareBoundaryPoints(Range.END_TO_START,d.range)<0)last=i;else break}
    return JSON.stringify([ranges[at].id??-1,ranges[last].id??-1])
}catch(e){return '[-1,-1]'}}
window.readerClear=()=>{for(const {doc} of view?.renderer?.getContents()||[])doc.defaultView.CSS?.highlights?.delete('narration')}
// The reader's own highlights: sentence ids marked by the person, drawn under the narration highlight.
let markIds=[]
function applyMarks(){
    for(const {doc,index} of view?.renderer?.getContents()||[]){
        const w=doc.defaultView;if(!w.CSS?.highlights)continue
        const ranges=mapDocument(doc,index).ranges.filter(r=>markIds.includes(r.id)).map(r=>r.range)
        if(ranges.length)w.CSS.highlights.set('marks',new w.Highlight(...ranges));else w.CSS.highlights.delete('marks')
    }
}
window.readerMarks=ids=>{markIds=ids||[];try{applyMarks()}catch(e){}}
// The sentence a search result opened: shown until the next page turn (the app clears it with -1).
let foundId=-1
function applyFound(){
    for(const {doc,index} of view?.renderer?.getContents()||[]){
        const w=doc.defaultView;if(!w.CSS?.highlights)continue
        const item=foundId<0?null:mapDocument(doc,index).ranges.find(r=>r.id===foundId)
        if(item)w.CSS.highlights.set('found',new w.Highlight(item.range));else w.CSS.highlights.delete('found')
    }
}
window.readerFound=id=>{foundId=id;try{applyFound()}catch(e){}}
// A long-press selection (and the Copy menu that rides on it) has done its job once narration starts.
window.readerSelection=()=>{for(const {doc} of view?.renderer?.getContents()||[]){const s=doc.getSelection()?.toString();if(s)return s}return ''}
// The first and last sentence the selection touches, as [first,last]; null when nothing is selected.
window.readerSelectionSpan=()=>{
    for(const {doc,index} of view?.renderer?.getContents()||[]){
        const sel=doc.getSelection();if(!sel||sel.isCollapsed||!sel.rangeCount)continue
        const range=sel.getRangeAt(0)
        const ids=mapDocument(doc,index).ranges.filter(r=>r.range.compareBoundaryPoints(Range.START_TO_END,range)>0&&r.range.compareBoundaryPoints(Range.END_TO_START,range)<0).map(r=>r.id)
        if(ids.length)return[Math.min(...ids),Math.max(...ids)]
    }
    return null
}
window.readerClearSelection=()=>{for(const {doc} of view?.renderer?.getContents()||[])try{doc.getSelection()?.removeAllRanges()}catch(e){}}
const flattenToc=(items,depth=0)=>(items||[]).flatMap(item=>[{...item,label:'  '.repeat(depth)+item.label},...flattenToc(item.subitems,depth+1)])
window.readerToc=()=>JSON.stringify(flattenToc(view.book.toc).map(item=>item.label))
window.readerTocGo=async index=>{quietUntil=0;userUntil=Date.now()+15000;await view.goTo(flattenToc(view.book.toc)[index].href)}
window.readerStyle=style=>{
    if(preview){deferredStyle=style;return}
    applyStyle(style)
}
function applyStyle(style){
    appearance=style;document.body.style.background=style.background
    if(!view?.renderer)return
    const family={literata:'Literata,serif',serif:'serif',sans:'sans-serif'}[style.typeface]
    const renderer=view.renderer
    quietUntil=Date.now()+1000
    // Entering the smooth-follow flow: record the paged geometry the reader is looking at right now,
    // from this very layout, before the flow changes.
    const entering=style.scroll&&!info?.cacheOnly&&renderer.getAttribute('flow')!=='scrolled'&&!!view.lastLocation?.range
    if(entering){pagedGeometry(view.lastLocation);kept=centerLine()}
    if(!style.scroll){releaseScroll();clearScrolledOverrides()}
    renderer.setAttribute('flow',style.scroll?'scrolled':'paginated')
    if(!style.scroll)setTimeout(clearScrolledOverrides,0)
    renderer.setAttribute('max-column-count','1');renderer.setAttribute('max-inline-size','900')
    // Room for a running head (chapter) and foot (progress, title) that are part of the page; the
    // scrolled flow keeps the same vertical spacing so switching flows does not move the text.
    renderer.setAttribute('margin','34px')
    Object.assign(renderer.style,{color:style.muted||style.foreground,fontFamily:'sans-serif',fontSize:'15px',letterSpacing:'.06em'})
    if(view.lastLocation)marginals(view.lastLocation)
    // Paged flow pads the view by the gap fraction g; scrolled flow pads the document by
    // -g/(g-1) of the width instead, so give it g/(1+g) to land on the same side margin.
    renderer.setAttribute('gap',`${Math.min(40,200*style.margin/Math.max(200,innerWidth))}%`)
    if(style.scroll)setTimeout(alignScrolled,150)
    renderer.removeAttribute('animated')
    renderer.setStyles(`
        @font-face{font-family:Literata;src:url('https://reader.local/fonts/Literata-Regular.ttf');font-style:normal;font-weight:400}
        @font-face{font-family:Literata;src:url('https://reader.local/fonts/Literata-Italic.ttf');font-style:italic;font-weight:400}
        @font-face{font-family:Literata;src:url('https://reader.local/fonts/Literata-SemiBold.ttf');font-style:normal;font-weight:600 900}
        html,body{background:${style.background}!important;color:${style.foreground}!important}
        body{font-size:${style.font}px!important;${family?`font-family:${family}!important;`:''}}
        p,li{line-height:${style.lineHeight}!important}
        p{margin-block-end:${style.paragraphSpace}px!important;${family?`text-align:${style.justify?'justify':'start'}!important;`:''}${style.justify?'hyphens:auto;':''}}
        img,svg{max-width:100%}a{color:inherit}
        ::selection{background:${style.highlight}}
        ::highlight(narration){background:${style.highlight};color:${style.foreground}}
        ::highlight(marks){background:rgba(236,201,75,.45)}
        ::highlight(found){background:rgba(0,183,188,.32)}
    `)
    applyGridShifts(style)
    if(style.scroll&&!info?.cacheOnly){
        // The same paint shows the aligned flow with the middle line exactly where it was.
        alignScrolled()
        const c=renderer.getContents()[0];if(c)holdScroll(scrollContainer(c.doc))
        if(entering){const before=kept&&{text:kept.text,y:kept.y};landKept();const after=centerLine();lastEngage={before,after:after&&{text:after.text,y:after.y}}}
    }
    clearTimeout(styleTimer)
    if(selected>=0)styleTimer=setTimeout(()=>window.readerGo(selected,highlightActive),180)
}
try{
    info=await (await fetch('/book-info')).json();view=document.createElement('foliate-view');document.body.append(view)
    view.addEventListener('load',({detail:{doc,index}})=>{
        if(appearance)applyGridShift(appearance,doc,index)
        if(info.cacheOnly)return
        mapDocument(doc,index)
        if(markIds.length)try{applyMarks()}catch(e){}
        if(foundId>=0)try{applyFound()}catch(e){}
        for(const event of ['touchstart','touchmove','touchend'])doc.addEventListener(event,e=>{
            if(!appearance.scroll)e.stopImmediatePropagation()
            if(event==='touchstart'&&Date.now()>quietUntil)userUntil=Date.now()+10000
        },{capture:true,passive:true})
        doc.addEventListener('click',e=>{
            if(e.target.closest?.('a')||!doc.getSelection()?.isCollapsed)return
            ReaderBridge.tap()
        })
        doc.addEventListener('selectionchange',()=>{
            if(preview)return
            const selection=doc.getSelection();if(!selection||selection.isCollapsed||!selection.rangeCount)return
            const range=selection.getRangeAt(0)
            const item=mapDocument(doc,index).ranges.find(r=>r.range.isPointInRange(range.startContainer,range.startOffset))
            if(item){selected=item.id;ReaderBridge.chosen(item.id)}
        })
    })
    view.addEventListener('relocate',({detail})=>{
        if(preview)return
        publishLocation(detail,Date.now()>=quietUntil&&Date.now()<=userUntil)
    })
    await view.open('/book.epub')
    window.readerStyle(info.style)
    if(info.cacheOnly)await view.renderer.goTo({index:0,anchor:0})
    else{
        await window.readerGo(info.selected,info.highlightActive)
        for(const {doc} of view.renderer.getContents())await doc.fonts.ready
        await window.readerGo(info.selected,info.highlightActive)
    }
    ReaderBridge.ready()
}catch(e){document.body.textContent='Unable to render this book: '+e.message;ReaderBridge.error(String(e))}
