/*
 * Copyright (c) 2014 Goran Ehrsson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.crm.contact

import grails.plugins.crm.core.CrmAddressInformation
import grails.plugins.crm.core.CrmContactInformation
import grails.plugins.crm.core.CrmEmbeddedAddress
import grails.plugins.crm.core.CrmEmbeddedContact
import grails.plugins.crm.core.DateUtils
import grails.plugins.crm.core.TenantUtils
import grails.plugins.crm.core.PagedResultList
import grails.plugins.crm.core.Pair
import groovy.transform.CompileStatic
import org.codehaus.groovy.grails.web.metaclass.BindDynamicMethod
import grails.plugins.crm.core.SearchUtils
import grails.plugins.selection.Selectable
import grails.events.Listener
import java.util.regex.Pattern

class CrmContactService {

    static transactional = true

    public static final String DEFAULT_ADDRESS_TYPE = 'postal'

    def grailsApplication
    def crmSecurityService
    def sequenceGeneratorService
    def crmTagService
    def messageSource

    @Listener(namespace = "crmContact", topic = "enableFeature")
    def enableFeature(event) {
        def tenant = crmSecurityService.getTenantInfo(event.tenant)
        if (!tenant) {
            throw new IllegalArgumentException("Cannot find tenant info for tenant [${event.tenant}], event=$event")
        }
        def locale = tenant.locale
        TenantUtils.withTenant(tenant.id) {
            // Initialize a number sequence for CrmContact
            def config = grailsApplication.config.crm.contact.sequence
            def start = config.start ?: 1L
            def format = config.format ?: "%s"
            sequenceGeneratorService.initSequence(CrmContact, null, tenant.id, start, format)

            crmTagService.createTag(name: CrmContact.name, multiple: true)

            // Postal address type
            def s = messageSource.getMessage("crmAddressType.name.postal", null, "Postal Address", locale)
            createAddressType(name: s, param: DEFAULT_ADDRESS_TYPE).save(failOnError: true)
            // Visit address type
            s = messageSource.getMessage("crmAddressType.name.visit", null, "Visit Address", locale)
            createAddressType(name: s, param: "visit").save(failOnError: true)
            // Delivery address type
            s = messageSource.getMessage("crmAddressType.name.delivery", null, "Delivery Address", locale)
            createAddressType(name: s, param: "delivery").save(failOnError: true)
            // Invoice address type
            s = messageSource.getMessage("crmAddressType.name.invoice", null, "Invoice Address", locale)
            createAddressType(name: s, param: "invoice").save(failOnError: true)

            // Relation type 'company'
            s = messageSource.getMessage("crmContactRelationType.name.company", null, "Company", locale)
            createRelationType(name: s, param: "company").save(failOnError: true)
        }
    }

    @Listener(namespace = "crmTenant", topic = "requestDelete")
    def requestDeleteTenant(event) {
        def tenant = event.id
        def count = 0
        count += CrmContactRelationType.countByTenantId(tenant)
        count += CrmContactCategoryType.countByTenantId(tenant)
        count += CrmAddressType.countByTenantId(tenant)
        count += CrmContact.countByTenantId(tenant)
        return count ? [namespace: 'crmContact', topic: 'deleteTenant'] : null
    }

    @Listener(namespace = "crmContact", topic = "deleteTenant")
    def deleteTenant(event) {
        def tenant = event.id
        def count = 0

        // Delete all child contacts
        def result = CrmContact.findAllByTenantIdAndParentIsNotNull(tenant)
        count += result.size()
        for (crmContact in result) {
            CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])
            crmContact.delete()
        }

        // Delete all parent contacts
        result = CrmContact.findAllByTenantId(tenant)
        count += result.size()
        for (crmContact in result) {
            CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])
            crmContact.delete()
        }

        CrmAddressType.findAllByTenantId(tenant)*.delete()
        CrmContactRelationType.findAllByTenantId(tenant)*.delete()
        CrmContactCategoryType.findAllByTenantId(tenant)*.delete()

        log.warn("Deleted $count contacts in tenant $tenant")
    }

    @CompileStatic
    CrmContact getContact(Long id) {
        getContactInternal(id, TenantUtils.tenant)
    }

    private CrmContact getContactInternal(Long id, Long tenant) {
        final CrmContact crmContact = CrmContact.get(id)
        crmContact?.tenantId == tenant ? crmContact : null
    }

    CrmContact findByNumber(String number) {
        CrmContact.findByNumberAndTenantId(number, TenantUtils.tenant, [cache: true])
    }

    CrmContact findByNumber2(String number) {
        CrmContact.findByNumber2AndTenantId(number, TenantUtils.tenant, [cache: true])
    }

    CrmContact findBySsn(String number) {
        CrmContact.findBySsnAndTenantId(number, TenantUtils.tenant, [cache: true])
    }

    CrmContact findByDuns(String number) {
        CrmContact.findByDunsAndTenantId(number, TenantUtils.tenant, [cache: true])
    }

    CrmContact findByGuid(String guid) {
        CrmContact.findByGuidAndTenantId(guid, TenantUtils.tenant, [cache: true])
    }

    CrmContact findByName(String name) {
        CrmContact.findByNameAndTenantId(name, TenantUtils.tenant, [cache: true])
    }

    CrmContact findByEmail(String email) {
        CrmContact.findByEmailAndTenantId(email, TenantUtils.tenant, [cache: true])
    }

    /**
     * Empty query = search all records.
     *
     * @param params pagination parameters
     * @return List of CrmContact domain instances
     */
    @Selectable
    PagedResultList<CrmContact> list(Map params = [:]) {
        list([:], params)
    }

    /**
     * Find CrmContact instances filtered by query.
     *
     * @param query filter parameters
     * @param params pagination parameters
     * @return List of CrmContact domain instances
     */
    @Selectable
    synchronized PagedResultList<CrmContact> list(Map query, Map params) {
        def result
        try {
            def totalCount = CrmContact.createCriteria().get {
                contactCriteria.delegate = delegate
                contactCriteria(query, true, null)
            }
            if (!params.sort) params.sort = 'name'
            if (!params.order) params.order = 'asc'
            def ids = CrmContact.createCriteria().list() {
                contactCriteria.delegate = delegate
                contactCriteria(query, false, params.sort)
                if (params.max != null) {
                    maxResults(params.max as Integer)
                }
                if (params.offset != null) {
                    firstResult(params.offset as Integer)
                }
                order(params.sort, params.order)
            }
            result = ids.size() ? ids.collect { CrmContact.get(it[0]) } : []
            result = new PagedResultList<CrmContact>(result, totalCount.intValue())
        } finally {
            contactCriteria.delegate = this
        }
        return result
    }

    @CompileStatic
    private boolean hasAddressQuery(Map query) {
        for (prop in ['address1', 'address2', 'address3', 'postalCode', 'city', 'region', 'country', 'timezone', 'latitude', 'longitude']) {
            if (query[prop]) {
                return true
            }
        }
        return false
    }

    private Set<Long> findRelatedIds(Object related, Boolean primary = null) {
        def resultA = CrmContactRelation.createCriteria().list() {
            projections {
                a {
                    property('id')
                }
            }
            if(primary != null) {
                eq('primary', primary)
            }
            if (related instanceof Number) {
                or {
                    b {
                        eq('id', ((Number) related).longValue())
                    }
                }
            } else {
                or {
                    b {
                        ilike('name', SearchUtils.wildcard(related))
                    }
                }
            }
        }.flatten() as Set
        def resultB = CrmContactRelation.createCriteria().list() {
            projections {
                b {
                    property('id')
                }
            }
            if(primary != null) {
                eq('primary', primary)
            }
            if (related instanceof Number) {
                or {
                    a {
                        eq('id', ((Number) related).longValue())
                    }
                }
            } else {
                or {
                    a {
                        ilike('name', SearchUtils.wildcard(related))
                    }
                }
            }
        }.flatten() as Set

        resultA + resultB
    }

    private static final Set<Long> NO_RESULT = [0L] as Set // A query value that will find nothing

    def contactCriteria = { query, count, sort ->
        Set<Long> ids = [] as Set
        if (query.tags) {
            def tagged = crmTagService.findAllIdByTag(CrmContact, query.tags)
            if (tagged) {
                ids.addAll(tagged)
            } else {
                ids = NO_RESULT
            }
        }

        if (query.related) {
            def related = findRelatedIds(query.related, null)
            if (related) {
                if(ids) {
                    if (ids != NO_RESULT) {
                        ids.retainAll(related)
                    }
                } else {
                    ids.addAll(related)
                }
            } else {
                ids = NO_RESULT
            }
        } else if (query.primary) {
            def related = findRelatedIds(query.primary, true)
            if (related) {
                if(ids) {
                    if (ids != NO_RESULT) {
                        ids.retainAll(related)
                    }
                } else {
                    ids.addAll(related)
                }
            } else {
                ids = NO_RESULT
            }
        }

        if(query.role) {
            def rels = CrmContactRelation.createCriteria().list {
                projections {
                    a {
                        property('id')
                    }
                    b {
                        property('id')
                    }
                }
                type {
                    or {
                        eq('param', query.role)
                        ilike('name', SearchUtils.wildcard(query.role))
                    }
                }
            }
            if(rels) {
                Set<Long> tmp = [] as Set
                for(tuple in rels) {
                    tmp.add(tuple[0])
                    tmp.add(tuple[1])
                }
                if(ids) {
                    if (ids != NO_RESULT) {
                        ids.retainAll(tmp)
                    }
                } else {
                    ids.addAll(tmp)
                }
            } else {
                ids = NO_RESULT
            }
        }

        projections {
            if (count) {
                countDistinct "id"
            } else {
                groupProperty "id"
                groupProperty sort
            }
        }

        eq('tenantId', TenantUtils.tenant)
        if (ids) {
            inList('id', ids)
        }
        if (query.guid) {
            eq('guid', query.guid)
        }
        if (query.number) {
            eq('number', query.number) // Exact match, no wildcard.
        }
        if (query.number2) {
            eq('number2', query.number2) // Exact match, no wildcard.
        }
        if (query.duns) {
            eq('duns', query.duns) // Exact match, no wildcard.
        }
        if (query.ssn) {
            ilike('ssn', SearchUtils.wildcard(query.ssn))
        }
        if (query.username) {
            ilike('username', SearchUtils.wildcard(query.username))
        }
        if (query.name) {
            ilike('name', SearchUtils.wildcard(query.name))
        }
        if (query.firstName || query.lastName) {
            if (query.firstName) {
                ilike('firstName', SearchUtils.wildcard(query.firstName))
            }
            if (query.lastName) {
                ilike('lastName', SearchUtils.wildcard(query.lastName))
            }
        } else {
            if (query.person && !query.company) {
                isNotNull('firstName')
            } else if (query.company && !query.person) {
                isNull('firstName')
            }
        }
        if (query.title) {
            ilike('title', SearchUtils.wildcard(query.title))
        }
        if (query.telephone) {
            ilike('telephone', SearchUtils.wildcard(query.telephone))
        }
        if (query.email) {
            ilike('email', SearchUtils.wildcard(query.email))
        }
        if (query.url) {
            ilike('url', SearchUtils.wildcard(query.url))
        }
        if (hasAddressQuery(query)) {
            addresses {
                if (query.address1) {
                    ilike('address1', SearchUtils.wildcard(query.address1))
                }
                if (query.address2) {
                    ilike('address2', SearchUtils.wildcard(query.address2))
                }
                if (query.address3) {
                    ilike('address3', SearchUtils.wildcard(query.address3))
                }
                if (query.postalCode) {
                    ilike('postalCode', SearchUtils.wildcard(query.postalCode))
                }
                if (query.city) {
                    ilike('city', SearchUtils.wildcard(query.city))
                }
                if (query.region) {
                    ilike('region', SearchUtils.wildcard(query.region))
                }
                if (query.country) {
                    ilike('country', SearchUtils.wildcard(query.country))
                }
            }
        }

        if (query.parent) {
            parent {
                if (query.parent instanceof Number) {
                    eq('id', ((Number) query.parent).longValue())
                } else {
                    ilike('name', SearchUtils.wildcard(query.parent.toString()))
                }
            }
        }

        if (query.dateCreated) {
            if (query.dateCreated[0] == '<') {
                lt('dateCreated', DateUtils.getDateSpan(DateUtils.parseDate(query.dateCreated.substring(1)))[0])
            } else if (query.dateCreated[0] == '>') {
                gt('dateCreated', DateUtils.getDateSpan(DateUtils.parseDate(query.dateCreated.substring(1)))[1])
            } else {
                def (am, pm) = DateUtils.getDateSpan(DateUtils.parseDate(query.dateCreated))
                between('dateCreated', am, pm)
            }
        }

        if (query.lastUpdated) {
            if (query.lastUpdated[0] == '<') {
                lt('lastUpdated', DateUtils.getDateSpan(DateUtils.parseDate(query.lastUpdated.substring(1)))[0])
            } else if (query.lastUpdated[0] == '>') {
                gt('lastUpdated', DateUtils.getDateSpan(DateUtils.parseDate(query.lastUpdated.substring(1)))[1])
            } else {
                def (am, pm) = DateUtils.getDateSpan(DateUtils.parseDate(query.lastUpdated))
                between('lastUpdated', am, pm)
            }
        }

        if (query.category) {
            // TODO Support collection of categories.
            categories {
                category {
                    or {
                        eq('param', query.category)
                        ilike('name', SearchUtils.wildcard(query.category))
                    }
                }
            }
        }
    }

    List<CrmAddressType> listAddressType(String name, Map params = [:]) {
        CrmAddressType.createCriteria().list(params) {
            eq('tenantId', TenantUtils.tenant)
            eq('enabled', params.enabled == null ? true : params.enabled)
            if (name) {
                or {
                    ilike('name', SearchUtils.wildcard(name))
                    eq('param', name)
                }
            }
            order 'orderIndex', 'asc'
        }
    }

    CrmAddressType getAddressType(String param) {
        CrmAddressType.findByParamAndTenantId(param, TenantUtils.tenant)
    }

    CrmAddressType getDefaultAddressType() {
        def type = getAddressType(DEFAULT_ADDRESS_TYPE)
        if (!type) {
            def tenantInfo = crmSecurityService.getTenantInfo(TenantUtils.tenant)
            def locale = tenantInfo ? tenantInfo.locale : Locale.getDefault()
            def s = messageSource.getMessage("crmAddressType.name.postal", null, "Postal Address", locale)
            type = createAddressType(name: s, param: DEFAULT_ADDRESS_TYPE, true)
        }
        return type
    }

    @CompileStatic
    private String paramify(final String name, Integer maxSize = 20) {
        String param = name.toLowerCase().replace(' ', '-')
        if (param.length() > maxSize) {
            param = param[0..(maxSize - 1)]
            if (param[-1] == '-') {
                param = param[0..-2]
            }
        }
        param
    }

    CrmAddressType createAddressType(Map params, boolean save = false) {
        def tenant = TenantUtils.tenant
        if (!params.param && params.name) {
            params.param = paramify(params.name, new CrmAddressType().constraints.param.maxSize)
        }
        def m = CrmAddressType.findByParamAndTenantId(params.param, tenant)
        if (!m) {
            m = new CrmAddressType()
            def args = [m, params, [include: CrmAddressType.BIND_WHITELIST]]
            new BindDynamicMethod().invoke(m, 'bind', args.toArray())
            m.tenantId = tenant
            if (params.enabled == null) {
                m.enabled = true
            }
            if (save) {
                m.save()
            } else {
                m.validate()
                m.clearErrors()
            }
        }
        return m
    }

    CrmContactRelationType getRelationType(String param) {
        CrmContactRelationType.findByParamAndTenantId(param, TenantUtils.tenant)
    }

    CrmContactRelationType createRelationType(Map params, boolean save = false) {
        def tenant = TenantUtils.tenant
        if (!params.param && params.name) {
            params.param = paramify(params.name, new CrmContactRelationType().constraints.param.maxSize)
        }
        def m = CrmContactRelationType.findByParamAndTenantId(params.param, tenant)
        if (!m) {
            m = new CrmContactRelationType()
            def args = [m, params, [include: CrmContactRelationType.BIND_WHITELIST]]
            new BindDynamicMethod().invoke(m, 'bind', args.toArray())
            m.tenantId = tenant
            if (params.enabled == null) {
                m.enabled = true
            }
            if (save) {
                m.save()
            } else {
                m.validate()
                m.clearErrors()
            }
        }
        return m
    }

    CrmContactRelation addRelation(CrmContact a, CrmContact b, Object typeOrParam, boolean primary, String description = null) {
        CrmContactRelationType type
        if (typeOrParam == null) {
            type = CrmContactRelationType.createCriteria().get() {
                order 'orderIndex', 'asc'
                maxResults 1
            }
        } else if (typeOrParam instanceof CrmContactRelationType) {
            type = typeOrParam
        } else {
            type = getRelationType(typeOrParam.toString())
            if (!type) {
                throw new IllegalArgumentException("CrmContactRelationType not found with param [$typeParam]")
            }
        }
        def relation = getRelation(a, b, type)
        if (!relation) {
            relation = new CrmContactRelation(a: a, b: b, type: type, primary: primary, description: description)
            relation.save(flush: true)
        }
        if (primary && !relation.hasErrors()) {
            setPrimaryRelation(relation)
        }
        relation
    }

    /**
     * Set a relation as primary relation.
     *
     * @param relation
     * @return true if primary relation on the contact was changed
     */
    boolean setPrimaryRelation(CrmContactRelation relation) {
        boolean rval = false
        relation.primary = true
        def existing = CrmContactRelation.createCriteria().list() {
            eq('a', relation.a)
            eq('primary', true)
            ne('id', relation.id)
        }
        for (rel in existing) {
            if (rel.primary) {
                rel.primary = false
                rval = true
            }
        }
        rval
    }

    CrmContactRelation getRelation(CrmContact a, CrmContact b, Object typeOrParam = null) {
        CrmContactRelationType type
        if (typeOrParam != null) {
            if (typeOrParam instanceof CrmContactRelationType) {
                type = typeOrParam
            } else {
                type = getRelationType(typeOrParam.toString())
                if (!type) {
                    throw new IllegalArgumentException("CrmContactRelationType not found with param [$typeOrParam]")
                }
            }
        }
        CrmContactRelation.createCriteria().get() {
            or {
                and {
                    eq('a', a)
                    eq('b', b)
                }
                and {
                    eq('a', b)
                    eq('b', a)
                }
            }
            if (type) {
                eq('type', type)
            }
        }
    }

    List<CrmContact> getRelatedContacts(CrmContact crmContact, Map params = [:]) {
        def ids = findRelatedIds(crmContact.id)
        def result
        if (ids) {
            ids.remove(crmContact.id) // Remove self.
            result = CrmContact.createCriteria().list(params) {
                inList('id', ids)
            }
        } else {
            result = []
        }
        result
    }

    List<CrmContactRelationType> listRelationType(String name, Map params = [:]) {
        CrmContactRelationType.createCriteria().list(params) {
            eq('tenantId', TenantUtils.tenant)
            eq('enabled', params.enabled == null ? true : params.enabled)
            if (name) {
                or {
                    ilike('name', SearchUtils.wildcard(name))
                    eq('param', name)
                }
            }
            order 'orderIndex', 'asc'
        }
    }

    CrmContactCategoryType getCategoryType(String param) {
        CrmContactCategoryType.findByParamAndTenantId(param, TenantUtils.tenant)
    }

    CrmContactCategoryType createCategoryType(Map params, boolean save = false) {
        def tenant = TenantUtils.tenant
        if (!params.param && params.name) {
            params.param = paramify(params.name, new CrmContactCategoryType().constraints.param.maxSize)
        }
        def m = CrmContactCategoryType.findByParamAndTenantId(params.param, tenant)
        if (!m) {
            m = new CrmContactCategoryType()
            def args = [m, params, [include: CrmContactCategoryType.BIND_WHITELIST]]
            new BindDynamicMethod().invoke(m, 'bind', args.toArray())
            m.tenantId = tenant
            if (params.enabled == null) {
                m.enabled = true
            }
            if (save) {
                m.save()
            } else {
                m.validate()
                m.clearErrors()
            }
        }
        return m
    }

    CrmContactCategory addCategory(CrmContact crmContact, String categoryParam, String categoryName = null) {
        def categoryType = getCategoryType(categoryParam)
        if (!categoryType) {
            if (categoryName) {
                categoryType = createCategoryType(name: categoryName, param: categoryParam, true)
            } else {
                throw new IllegalArgumentException("CrmContactCategoryType not found with param [$categoryParam]")
            }
        }
        def category = CrmContactCategory.createCriteria().get() {
            eq('contact', crmContact)
            eq('category', categoryType)
        }
        if (!category) {
            category = new CrmContactCategory(contact: crmContact, category: categoryType)
            if (!category.hasErrors()) {
                crmContact.addToCategories(category)
                crmContact.save()
            }
        }
        return category
    }

    List<CrmContactCategoryType> listCategoryType(String name, Map params = [:]) {
        CrmContactCategoryType.createCriteria().list(params) {
            eq('tenantId', TenantUtils.tenant)
            if (name) {
                or {
                    ilike('name', SearchUtils.wildcard(name))
                    eq('param', name)
                }
            }
        }
    }

    String deleteCategoryType(CrmContactCategoryType category) {
        def tombstone = category.toString()
        def id = category.id
        def tenant = category.tenantId

        category.delete()

        def username = crmSecurityService.currentUser?.username
        event(for: "crmContactCategoryType", topic: "deleted", data: [id: id, tenant: tenant, user: username, name: tombstone])
        return tombstone
    }

    /**
     * Create an CrmEmbeddedAddress instance populated with specified values.
     * @param values see CrmEmbeddedAddress for valid properties
     * @return a CrmEmbeddedAddress instance
     */
    CrmAddressInformation createAddressInformation(Map<String, Object> values) {
        def m = new CrmEmbeddedAddress()
        def args = [m, values]
        new BindDynamicMethod().invoke(m, 'bind', args.toArray())
        return m
    }

    /**
     * Create an CrmEmbeddedContact instance populated with specified values.
     * @param values see CrmEmbeddedContact for valid properties
     * @return a CrmEmbeddedContact instance
     */
    CrmContactInformation createContactInformation(Map<String, Object> values) {
        def m = new CrmEmbeddedContact()
        if (values.company && !values.companyName) {
            values.companyName = values.company
        }
        def args = [m, values]
        new BindDynamicMethod().invoke(m, 'bind', args.toArray())
        def address = values.address
        if (address) {
            if (address instanceof String) {
                address = [address1: address]
            }
            /*
             * CrmEmbeddedContact inherits from CrmAddress so all address properties are in the contact instance itself.
             */
            args = [m, address]
            new BindDynamicMethod().invoke(m, 'bind', args.toArray())
        }
        return m
    }

    CrmContact createCompany(Map<String, Object> params, boolean save = false) {
        if (!params.name) {
            throw new IllegalArgumentException("Mandatory parameter [name] is missing")
        }
        def tenant = TenantUtils.tenant
        def crmContact = new CrmContact(tenantId: tenant)
        def args = [crmContact, params, [include: CrmContact.BIND_WHITELIST]]
        new BindDynamicMethod().invoke(crmContact, 'bind', args.toArray())

        if (!crmContact.username) {
            crmContact.username = crmSecurityService.currentUser?.username
        }

        def allAddresses = []
        if (params.address) {
            allAddresses << params.address
        }
        if (params.addresses) {
            allAddresses.addAll(params.addresses)
        }
        def preferred = true
        for (address in allAddresses) {
            if (address.type instanceof CrmAddressType) {
                // Nothing
            } else if (address.type) {
                def tmp = getAddressType(address.type.toString())
                if (!tmp) {
                    throw new IllegalArgumentException("Address type [${address.type}] not found")
                }
                address.type = tmp
            } else {
                address.type = getDefaultAddressType()
            }
            if (address.preferred == null) {
                address.preferred = preferred
                preferred = false
            }
            def crmContactAddress = new CrmContactAddress(contact: crmContact)
            def addressArgs = [crmContactAddress, address]
            new BindDynamicMethod().invoke(crmContactAddress, 'bind', addressArgs.toArray())
            if (crmContactAddress.validate()) {
                crmContact.addToAddresses(crmContactAddress)
            } else {
                for (err in crmContactAddress.errors.allErrors) {
                    crmContact.errors.rejectValue('address', err.code, err.arguments, err.defaultMessage)
                }
            }
        }

        if (save) {
            setNumberUnique(crmContact)
            if (!crmContact.hasErrors()) {
                crmContact.save()
            }
        } else {
            crmContact.validate()
            crmContact.clearErrors()
        }

        return crmContact
    }

    CrmContact createPerson(Map<String, Object> params, boolean save = false) {
        if (!(params.firstName || params.lastName)) {
            throw new IllegalArgumentException("Mandatory parameter [firstName or lastName] is missing")
        }
        def tenant = TenantUtils.tenant
        def crmContact = new CrmContact(tenantId: tenant)
        def args = [crmContact, fixFirstLastName(params), [include: CrmContact.BIND_WHITELIST + ['parent']]]
        new BindDynamicMethod().invoke(crmContact, 'bind', args.toArray())

        if (!crmContact.username) {
            crmContact.username = crmSecurityService.currentUser?.username
        }

        def allAddresses = []
        if (params.address) {
            allAddresses << params.address
        }
        if (params.addresses) {
            allAddresses.addAll(params.addresses)
        }
        def preferred = true
        for (address in allAddresses) {
            if (address.type instanceof CrmAddressType) {
                // Nothing
            } else if (address.type) {
                def tmp = getAddressType(address.type.toString())
                if (!tmp) {
                    throw new IllegalArgumentException("Address type [${address.type}] not found")
                }
                address.type = tmp
            } else {
                address.type = getDefaultAddressType()
            }
            if (address.preferred == null) {
                address.preferred = preferred
                preferred = false
            }
            def crmContactAddress = new CrmContactAddress(contact: crmContact)
            def addressArgs = [crmContactAddress, address]
            new BindDynamicMethod().invoke(crmContactAddress, 'bind', addressArgs.toArray())
            if (crmContactAddress.validate()) {
                crmContact.addToAddresses(crmContactAddress)
            } else {
                for (err in crmContactAddress.errors.allErrors) {
                    crmContact.errors.rejectValue('address', err.code, err.arguments, err.defaultMessage)
                }
            }
        }

        if (save) {
            setNumberUnique(crmContact)
            if (!crmContact.hasErrors()) {
                def related = params.related
                if (crmContact.save() && related) {
                    if (related instanceof List) {
                        addRelation(crmContact, related[0], related[1], true)
                    } else {
                        addRelation(crmContact, related, null, true)
                    }
                }
            }
        } else {
            crmContact.validate()
            crmContact.clearErrors()
        }

        return crmContact
    }

    private String setNumberUnique(CrmContact crmContact) {
        def tenant = TenantUtils.tenant
        def number = crmContact.number
        CrmContact.withNewSession {
            if (!number) {
                number = sequenceGeneratorService.nextNumber(CrmContact, null, tenant)
            }
            while (CrmContact.createCriteria().count() {
                eq('tenantId', tenant)
                eq('number', number)
            }) {
                log.warn "Sequence number [$number] for CrmContact was occupied, trying next..."
                number = sequenceGeneratorService.nextNumber(CrmContact, null, tenant)
            }
        }
        if (crmContact.number != number) {
            crmContact.number = number
        }
        return number
    }

    private Map getAddressProperties(CrmContact crmContact) {
        def sourceAddress = crmContact.address
        def destAddress = CrmContactCommand.ADDR_PROPS.inject([:]) { map, key ->
            map[key] = sourceAddress[key]
            map
        }
        destAddress.type = sourceAddress.type
        destAddress.preferred = true
        return destAddress
    }

    private Map fixFirstLastName(final Map params) {
        if (params.firstName && !params.lastName) {
            String[] tmp = params.firstName.split(' ')
            params.firstName = tmp[0]
            if (tmp.length > 1) {
                params.lastName = tmp[1..-1].join(' ')
            }
        }
        return params
    }

    private Map extractAddress(final Map params, String prefix = 'address') {
        def address = params.remove(prefix)
        def keys = params.keySet().toList() // toList() because clone not supported

        for (p in keys) {
            if (p.startsWith(prefix + '.')) {
                params.remove(p)
            }
        }
        return address
    }

    /**
     * Given a Map with customer.id, customer.name, contact.id and contact.name
     * create CrmContact instances using xxxxx.name if xxxxx.id is null
     * The created person (contact.name) will be related to the company (customer.name).
     *
     * @param params Map with parameters
     * @return a Pair with two CrmContact instances, company and employee.
     */
    Pair<CrmContact, CrmContact> fixCustomerParams(Map params) {
        def company
        def contact
        if (params.customer instanceof CrmContact) {
            company = params.customer
        } else if (params['customer.id']) {
            company = getContact(Long.valueOf(params['customer.id'].toString()))
        }
        if (params.contact instanceof CrmContact) {
            contact = params.contact
        } else if (params['contact.id']) {
            contact = getContact(Long.valueOf(params['contact.id'].toString()))
        }

        if (company == null) {
            def primaryContact = contact?.primaryContact
            if (primaryContact) {
                // Company is not specified but the selected person is associated with a company (primaryContact)
                // Set params as if the user had selected the person's primary contact in the company field.
                company = primaryContact
                params['customer.name'] = company.name
                params['customer.id'] = company.id
            }
        }

        // A company name is specified but it's not an existing company.
        // Create a new company.
        if (params['customer.name'] && !company) {
            company = createCompany(name: params['customer.name']).save(failOnError: true, flush: true)
            params['customer.id'] = company.id
        }

        // A person name is specified but it's not an existing person.
        // Create a new person.
        if (params['contact.name'] && !contact) {
            contact = createPerson([firstName: params['contact.name'], related: company]).save(failOnError: true, flush: true)
            params['contact.id'] = contact.id
        }

        new Pair(company, contact)
    }

    CrmContact save(Map params) {
        def tenant = TenantUtils.tenant
        def number = params.remove('number')
        def name = params.remove('name')
        def firstName = params.remove('firstName')
        def lastName = params.remove('lastName')
        def title = params.remove('title')
        def address = params.remove('address')
        def keys = params.keySet().toList() // toList() because clone not supported

        for (p in keys) {
            if (p.startsWith('address.')) {
                params.remove(p)
            }
        }

        def addressEntered = false
        if (address) {
            // Change empty strings to null
            for (prop in address.keySet()) {
                if (address[prop]?.trim()) {
                    addressEntered = true
                } else {
                    address[prop] = null
                }
            }
            if (!address.type) {
                address.type = DEFAULT_ADDRESS_TYPE
            }
            if (address.latitude) {
                address.latitude = Float.valueOf(address.latitude)
            }
            if (address.longitude) {
                address.longitude = Float.valueOf(address.longitude)
            }
        } else {
            address = [:]
        }

        def crmContact
        if (name) {
            if (firstName || lastName) {
                // Find company.
                def company = CrmContact.findByNameAndTenantId(name, tenant)
                if (!company) {
                    // Create new company!
                    company = new CrmContact()
                    company.properties = params // TODO bind data using white list!
                    company.name = name
                    company.tenantId = tenant
                    if (addressEntered) {
                        company.addToAddresses(new CrmContactAddress(address))
                    }
                    setNumberUnique(company)
                    company.save(failOnError: true, flush: true)
                }
                if (params.id) {
                    crmContact = getContactInternal(Long.valueOf(params.id), tenant)
                }
                if (!crmContact) {
                    crmContact = new CrmContact()
                    if (firstName && !lastName) {
                        def tmp = firstName.split(' ')
                        firstName = tmp[0]
                        if (tmp.size() > 1) {
                            lastName = tmp[1..-1].join(' ')
                        }
                    }
                }
                crmContact.properties = params // TODO bind data using white list!
                crmContact.number = number
                crmContact.firstName = firstName
                crmContact.lastName = lastName
                crmContact.title = title
                if ((!addressEntered) && company.address) {
                    def companyAddress = company.address
                    address = CrmContactCommand.ADDR_PROPS.inject([:]) { map, key ->
                        map[key] = companyAddress[key]
                        map
                    }
                    address.type = companyAddress.type
                    address.preferred = true
                }
                if (address) {
                    def a = crmContact.address
                    if (!a) {
                        a = new CrmContactAddress(address)
                        crmContact.addToAddresses(a)
                    }
                    println "Updatring address $address"
                    a.properties = address
                }
                crmContact.parent = company
                crmContact.tenantId = tenant
                setNumberUnique(crmContact)
                if (crmContact.validate()) {
                    crmContact.save(flush: true)
                } else {
                    company.discard()
                }
            } else {
                // Create a company, not a person.
                if (params.id) {
                    crmContact = getContactInternal(Long.valueOf(params.id), tenant)
                }
                if (!crmContact) {
                    crmContact = new CrmContact()
                    if (address) {
                        crmContact.addToAddresses(new CrmContactAddress(address))
                    }
                } else {
                    crmContact.address = address
                }
                crmContact.properties = params // TODO bind data using white list!
                crmContact.name = name
                crmContact.tenantId = tenant
                crmContact.number = number
                setNumberUnique(crmContact)
                if (crmContact.validate()) {
                    crmContact.save(flush: true)
                }
            }
        } else if (firstName || lastName) {
            if (params.id) {
                crmContact = getContactInternal(Long.valueOf(params.id), tenant)
            }
            if (!crmContact) {
                crmContact = new CrmContact()
                if (address) {
                    crmContact.addToAddresses(new CrmContactAddress(address))
                }
            } else {
                crmContact.address = address
            }
            crmContact.properties = params // TODO bind data using white list!
            crmContact.firstName = firstName
            crmContact.lastName = lastName
            crmContact.title = title
            crmContact.tenantId = tenant
            crmContact.number = number
            setNumberUnique(crmContact)
            if (crmContact.validate()) {
                crmContact.save(flush: true)
            }
        }

        return crmContact
    }

    String deleteContact(CrmContact crmContact) {
        def tombstone = crmContact.toString()
        def id = crmContact.id
        def tenant = crmContact.tenantId
        def username = crmSecurityService.currentUser?.username

        event(for: "crmContact", topic: "delete", fork: false, data: [id: id, tenant: tenant, user: username, name: tombstone])

        // CrmContactRelation has no belongsTo (CrmContact) so we must manually delete all relations first.
        CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])

        crmContact.delete(flush: true)
        log.debug "Deleted contact #$id in tenant $tenant \"${tombstone}\""

        event(for: "crmContact", topic: "deleted", data: [id: id, tenant: tenant, user: username, name: tombstone])

        return tombstone
    }

    private static final List GUESS_REFERENCE_PATTERNS = []

    @Listener(namespace = "crm", topic = "guessReference")
    def guessReference(event) {
        // Lazy init regex patterns.
        // TODO patterns are set in Config.groovy, they are not localized!
        // event.locale contains the client Locale object. How can we lookup
        // patterns bases on locale?
        if (GUESS_REFERENCE_PATTERNS.isEmpty()) {
            def patterns = grailsApplication.config.crm.contact.reference.patterns
            if (patterns) {
                synchronized (GUESS_REFERENCE_PATTERNS) {
                    if (GUESS_REFERENCE_PATTERNS.isEmpty()) {
                        for (p in patterns) {
                            GUESS_REFERENCE_PATTERNS << Pattern.compile(p)
                        }
                    }
                }
            }
        }

        def tenant = event.tenant ?: TenantUtils.tenant
        def text = event.text.toLowerCase()
        for (Pattern p in GUESS_REFERENCE_PATTERNS) {
            def m = p.matcher(text)
            if (m.find()) {
                def name = m.group(1)
                def result = CrmContact.withCriteria() {
                    projections {
                        property('id')
                        property('name')
                    }
                    eq('tenantId', tenant)
                    or {
                        eq('name', name, [ignoreCase: true])
                        eq('firstName', name, [ignoreCase: true])
                        eq('lastName', name, [ignoreCase: true])
                    }
                    maxResults 100
                    cache true
                }.collect { [id: 'crmContact@' + it[0], text: it[1]] }
                if (result) {
                    return result
                }
            }
        }
        return null
    }

}
