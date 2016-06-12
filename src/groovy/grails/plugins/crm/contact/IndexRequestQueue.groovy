package grails.plugins.crm.contact

import grails.util.GrailsNameUtils

/**
 * Holds objects to be indexed.
 * <br/>
 * NOTE: This is shared class, so need to be thread-safe.
 */
class IndexRequestQueue {

    /**
     * A map containing the pending index requests.
     */
    private Map<String, Object> indexRequests = [:]

    /**
     * A set containing the pending delete requests.
     */
    private Set<String> deleteRequests = []

    private String getKey(instance) {
        GrailsNameUtils.getPropertyName(instance.class) + '@' + instance.id
    }

    void addIndexRequest(instance) {
        indexRequests.put(getKey(instance), instance)
    }

    void addDeleteRequest(instance) {
        synchronized (this) {
            deleteRequests.add(getKey(instance))
        }
    }

    void executeRequests() {
        Map<String, Object> toIndex = [:]
        Set<String> toDelete = []

        // Copy existing queue to ensure we are interfering with incoming requests.
        synchronized (this) {
            toIndex.putAll(indexRequests)
            toDelete.addAll(deleteRequests)
            indexRequests.clear()
            deleteRequests.clear()
        }

        // If there are domain instances that are both in the index requests & delete requests list,
        // they are directly deleted.
        toIndex.keySet().removeAll(toDelete)

        // If there is nothing in the queues, just stop here
        if (toIndex.isEmpty() && toDelete.empty) {
            return
        }

        toIndex.each { key, value ->
            println "Indexing $key $value ..."
        }

        // Execute delete requests
        toDelete.each {
            println "Deleting $it ..."
        }
    }
}
