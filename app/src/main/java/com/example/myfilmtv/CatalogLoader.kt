package com.example.myfilmtv

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded worker: foreground requests interrupt background prefetch between pages. */
class CatalogLoader(private val store:CatalogStore) {
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    @Volatile private var activeEpoch=0
    @Volatile private var backgroundEnabled=true
    @Volatile private var sourceList:List<Source> = emptyList()
    @Volatile var onPrefetched:(()->Unit)? = null
    @Volatile private var categoryMap:Map<String,List<Network.Category>> = emptyMap()
    fun configure(sources:List<Source>,categories:Map<String,List<Network.Category>>) {
        activeEpoch++; sourceList=sources.filter{it.enabled}; categoryMap=categories.toMap()
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
        val epoch=++activeEpoch
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
            if(epoch==activeEpoch) Network.main.post { if(epoch==activeEpoch) onPage(store.movies(group),fetched,hasMore) }
        }
    }
    fun prefetch() {
        val epoch=activeEpoch
        worker.execute {
            if(!backgroundEnabled || epoch!=activeEpoch) return@execute
            // Finite warmup; not a whole-library crawl at launch.
            for(group in listOf("movie","series","anime","variety","short")) {
                if(epoch!=activeEpoch || !backgroundEnabled) break
                sourceList.forEach { s ->
                    if(epoch!=activeEpoch || !backgroundEnabled) return@execute
                    val ids=groupIds(s,group)
                    for(id in ids.take(2)) {
                        val c=store.cursor(group,s.id,id)
                        if(c.finished || c.nextPage>2) continue
                        val result=runCatching { Network.page(s,page=c.nextPage,typeId=id) }.getOrNull() ?: continue
                        store.savePage(group,s.id,id,result.items,c.nextPage+1,!result.hasNext || result.items.isEmpty())
                        Network.main.post { onPrefetched?.invoke() }
                    }
                }
            }
        }
    }
    fun pause() { backgroundEnabled=false; activeEpoch++ }
    fun resume() { backgroundEnabled=true }
    fun invalidate() { activeEpoch++ }
    fun shutdown() { activeEpoch++; worker.shutdownNow() }
}
