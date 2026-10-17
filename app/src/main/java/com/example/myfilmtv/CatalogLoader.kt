package com.example.myfilmtv

import java.util.concurrent.Executors

/** One bounded worker: foreground requests interrupt background prefetch between pages. */
class CatalogLoader(private val store:CatalogStore) {
    private val worker=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    @Volatile private var activeEpoch=0
    @Volatile private var backgroundEnabled=true
    @Volatile private var sourceList:List<Source> = emptyList()
    @Volatile var onPrefetched:(()->Unit)? = null
    @Volatile private var backgroundStarted=false
    @Volatile private var categoryMap:Map<String,List<Network.Category>> = emptyMap()
    fun configure(sources:List<Source>,categories:Map<String,List<Network.Category>>) {
        activeEpoch++; backgroundStarted=false; sourceList=sources.filter{it.enabled}; categoryMap=categories.toMap()
    }
    fun groupIds(source:Source,group:String):List<String> {
        val cats=categoryMap[source.id].orEmpty()
        val root=cats.filter { Network.categoryGroup(it.name)==group }
        if(root.isEmpty()) return emptyList()
        // Prefer a true parent category; otherwise enumerate matching leaf categories.
        val parent=root.filter { r -> cats.any { it.parentId==r.id } }
        return if(parent.isNotEmpty()) parent.map {it.id}.distinct() else root.map {it.id}.distinct()
    }
    fun currentEpoch() = activeEpoch
    fun foreground(group:String, onPage:(List<Movie>,Int,Boolean)->Unit) {
        val epoch=activeEpoch
        worker.execute {
            val sources=sourceList
            val cursors=sources.flatMap { s -> groupIds(s,group).map { s to it } }
            var fetched=0
            var hasMore=false
            // At most one page per source/category cursor on a foreground request.
            for((s,id) in cursors) {
                if(epoch!=activeEpoch) return@execute
                val cursor=store.cursor(group,s.id,id)
                if(cursor.finished) continue
                val r=runCatching { Network.page(s,page=cursor.nextPage,typeId=id) }.getOrNull() ?: continue
                if(epoch!=activeEpoch) return@execute
                store.savePage(group,s.id,id,r.items,cursor.nextPage+1,!r.hasNext || r.items.isEmpty())
                fetched+=r.items.size
                // Final callback below prevents overlapping pagination requests.
            }
            hasMore=cursors.any { (s,id) -> !store.cursor(group,s.id,id).finished }
            if(epoch==activeEpoch) Network.main.post { if(epoch==activeEpoch) onPage(emptyList(),fetched,hasMore) }
        }
    }
    fun prefetch() {
        if(backgroundStarted) return
        backgroundStarted=true
        val epoch=activeEpoch
        prefetchWorker.execute {
            val failures=mutableSetOf<String>()
            while(backgroundEnabled && epoch==activeEpoch) {
                var progressed=false
                for(group in listOf("movie","series","anime","variety","short")) {
                    if(epoch!=activeEpoch || !backgroundEnabled) break
                    for(s in sourceList) for(id in groupIds(s,group)) {
                        if(epoch!=activeEpoch || !backgroundEnabled) break
                        val key="$group|${s.id}|$id"
                        if(key in failures) continue
                        val cursor=store.cursor(group,s.id,id)
                        if(cursor.finished) continue
                        val r=runCatching{Network.page(s,page=cursor.nextPage,typeId=id)}.getOrNull()
                        if(r==null) {failures.add(key);continue}
                        store.savePage(group,s.id,id,r.items,cursor.nextPage+1,!r.hasNext || r.items.isEmpty())
                        progressed=true
                        Network.main.post { onPrefetched?.invoke() }
                        // Yield between requests; never run an unbounded network burst.
                        try { Thread.sleep(350) } catch (_:InterruptedException){return@execute}
                    }
                }
                if(!progressed) break
            }
            backgroundStarted=false
        }
    }
    fun pause() { backgroundEnabled=false; activeEpoch++ }
    fun resume() { backgroundEnabled=true; backgroundStarted=false }
    fun invalidate() { activeEpoch++ }
    fun shutdown() { activeEpoch++; worker.shutdownNow(); prefetchWorker.shutdownNow() }
}
