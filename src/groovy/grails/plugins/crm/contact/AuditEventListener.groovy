package grails.plugins.crm.contact

import grails.util.GrailsNameUtils
import org.apache.log4j.Logger
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEventListener
import org.grails.datastore.mapping.engine.event.PostDeleteEvent
import org.grails.datastore.mapping.engine.event.PostInsertEvent
import org.grails.datastore.mapping.engine.event.PostUpdateEvent
import org.springframework.context.ApplicationEvent
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Created by goran on 2016-03-18.
 */
class AuditEventListener extends AbstractPersistenceEventListener {

    private static final Logger log = Logger.getLogger(this)

    /** List of pending objects to reindex. */
    static ThreadLocal<Map> pendingObjects = new ThreadLocal<Map>()

    /** List of pending object to delete */
    static ThreadLocal<Map> deletedObjects = new ThreadLocal<Map>()

    private IndexRequestQueue indexRequestQueue = new IndexRequestQueue()

    AuditEventListener(Datastore datastore) {
        super(datastore)
    }

    @Override
    protected void onPersistenceEvent(AbstractPersistenceEvent event) {
        if (event instanceof PostInsertEvent) {
            onPostInsert(event)
        } else if (event instanceof PostUpdateEvent) {
            onPostUpdate(event)
        } else if (event instanceof PostDeleteEvent) {
            onPostDelete(event)
        }
    }

    @Override
    boolean supportsEventType(Class<? extends ApplicationEvent> aClass) {
        [PostInsertEvent, PostUpdateEvent, PostDeleteEvent].any() { it.isAssignableFrom(aClass) }
    }

    void registerMySynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                if (sync instanceof IndexSynchronization) {
                    // already registered.
                    return
                }
            }
            TransactionSynchronizationManager.registerSynchronization(new IndexSynchronization(indexRequestQueue, this))
        }
    }

    /**
     * Push object to index. Save as pending if transaction is not committed yet.
     */
    void pushToIndex(entity) {
        // Register transaction synchronization
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Save object as pending
            def objs = pendingObjects.get()
            if (!objs) {
                objs = [:]
                pendingObjects.set(objs)
            }

            def key = GrailsNameUtils.getPropertyName(entity.class) + '@' + entity.id
            if (deletedObjects.get()) {
                deletedObjects.get().remove(key)
            }
            objs[key] = entity
            registerMySynchronization()
            log.debug("Adding $key for later")
        } else {
            indexRequestQueue.addIndexRequest(entity)
            indexRequestQueue.executeRequests()
            log.debug("Adding $entity immediately")
        }
    }

    void pushToDelete(entity) {
        // Register transaction synchronization
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Add to list of deleted
            def objs = deletedObjects.get()
            if (!objs) {
                objs = [:]
                deletedObjects.set(objs)
            }

            def key = GrailsNameUtils.getPropertyName(entity.class) + '@' + entity.id
            if (pendingObjects.get()) {
                pendingObjects.get().remove(key)
            }
            objs[key] = entity
            registerMySynchronization()
            log.debug("Delete $key for later")
        } else {
            indexRequestQueue.addDeleteRequest(entity)
            indexRequestQueue.executeRequests()
            log.debug("Delete $entity immediately")
        }
    }

    void onPostInsert(PostInsertEvent event) {
        def entity = getEventEntity(event)
        if (!entity) {
            log.warn('Received a PostInsertEvent with no entity')
            return
        }
        if (entity instanceof CrmContact) {
            pushToIndex(entity)
        }
    }

    void onPostUpdate(PostUpdateEvent event) {
        def entity = getEventEntity(event)
        if (!entity) {
            log.warn('Received a PostUpdateEvent with no entity')
            return
        }
        if (entity instanceof CrmContact) {
            pushToIndex(entity)
        }
    }

    void onPostDelete(PostDeleteEvent event) {
        def entity = getEventEntity(event)
        if (!entity) {
            log.warn('Received a PostDeleteEvent with no entity')
            return
        }
        if (entity instanceof CrmContact) {
            pushToDelete(entity)
        }
    }

    Map getPendingObjects() {
        pendingObjects.get()
    }

    Map getDeletedObjects() {
        deletedObjects.get()
    }

    void clearPendingObjects() {
        pendingObjects.remove()
    }

    void clearDeletedObjects() {
        deletedObjects.remove()
    }

    private def getEventEntity(AbstractPersistenceEvent event) {
        if (event.entityAccess) {
            return event.entityAccess.entity
        }

        event.entityObject
    }

}
