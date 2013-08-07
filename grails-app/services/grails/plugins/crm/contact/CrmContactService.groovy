/*
 *  Copyright 2012 Goran Ehrsson.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package grails.plugins.crm.contact

import grails.plugins.crm.core.TenantUtils
import grails.plugins.crm.core.PagedResultList
import org.codehaus.groovy.grails.web.metaclass.BindDynamicMethod
import grails.plugins.crm.core.SearchUtils
import grails.events.Listener
import java.util.regex.Pattern

class CrmContactService {

    static transactional = true

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

            sequenceGeneratorService.initSequence(CrmContact, null, tenant.id, null, "%s")

            crmTagService.createTag(name: CrmContact.name, multiple: true)

            // Postal address type
            def s = messageSource.getMessage("crmAddressType.name.postal", null, "Postadress", locale)
            createAddressType(name: s, param: "postal").save(failOnError: true)
            // Visit address type
            s = messageSource.getMessage("crmAddressType.name.visit", null, "Besöksadress", locale)
            createAddressType(name: "Besöksadress", param: "visit").save(failOnError: true)
            // Delivery address type
            s = messageSource.getMessage("crmAddressType.name.delivery", null, "Leveransadress", locale)
            createAddressType(name: "Leveransadress", param: "delivery").save(failOnError: true)
            // Invoice address type
            s = messageSource.getMessage("crmAddressType.name.invoice", null, "Fakturadress", locale)
            createAddressType(name: "Fakturaadress", param: "invoice").save(failOnError: true)
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
        for(crmContact in result) {
            CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])
            crmContact.delete()
        }

        // Delete all parent contacts
        result = CrmContact.findAllByTenantId(tenant)
        count += result.size()
        for(crmContact in result) {
            CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])
            crmContact.delete()
        }

        CrmAddressType.findAllByTenantId(tenant)*.delete()
        CrmContactRelationType.findAllByTenantId(tenant)*.delete()
        CrmContactCategoryType.findAllByTenantId(tenant)*.delete()

        log.warn("Deleted $count contacts in tenant $tenant")
    }

    CrmContact getContact(Long id) {
        CrmContact.findByIdAndTenantId(id, TenantUtils.tenant, [cache: true])
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

    /**
     * Empty query = search all records.
     *
     * @param params pagination parameters
     * @return List of CrmContact domain instances
     */
    def list(Map params = [:]) {
        list([:], params)
    }

    /**
     * Find CrmContact instances filtered by query.
     *
     * @param query filter parameters
     * @param params pagination parameters
     * @return List of CrmContact domain instances
     */
    synchronized def list(Map query, Map params) {
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

    private boolean hasAddressQuery(Map query) {
        for (prop in ['address1', 'address2', 'address3', 'postalCode', 'city', 'region', 'country', 'timezone', 'latitude', 'longitude']) {
            if (query[prop]) {
                return true
            }
        }
        return false
    }

    def contactCriteria = { query, count, sort ->
        def tagged
        if (query.tags) {
            tagged = crmTagService.findAllByTag(CrmContact, query.tags).collect { it.id }
            if (!tagged) {
                tagged = [0L] // Force no search result.
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
        if (tagged) {
            inList('id', tagged)
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
                    eq('id', query.parent)
                } else {
                    ilike('name', SearchUtils.wildcard(query.parent.toString()))
                }
            }
        }

        if(query.category) {
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

    CrmAddressType getAddressType(String param) {
        CrmAddressType.findByParamAndTenantId(param, TenantUtils.tenant)
    }

    private String paramify(String name, Integer maxSize = 20) {
        def param = name.toLowerCase().replace(' ', '-')
        if (param.length() > maxSize) {
            param = param[0..(maxSize - 1)]
            if (param[-1] == '-') {
                param = param[0..-2]
            }
        }
        return param
    }

    CrmAddressType createAddressType(Map params, boolean save = false) {
        def tenant = TenantUtils.tenant
        if (!params.param) {
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
        if (!params.param) {
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

    CrmContactRelation addRelation(CrmContact a, CrmContact b, String typeParam, String description = null) {
        def type = getRelationType(typeParam)
        if (!type) {
            throw new IllegalArgumentException("CrmContactRelationType not found with param [$typeParam]")
        }
        def relation = CrmContactRelation.createCriteria().get() {
            eq('a', a)
            eq('b', b)
            eq('type', type)
        }
        if (!relation) {
            relation = new CrmContactRelation(a: a, b: b, type: type, description: description).save(failOnError: true)
        }
        return relation
    }

    CrmContactCategoryType getCategoryType(String param) {
        CrmContactCategoryType.findByParamAndTenantId(param, TenantUtils.tenant)
    }

    CrmContactCategoryType createCategoryType(Map params, boolean save = false) {
        def tenant = TenantUtils.tenant
        if (!params.param) {
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
            if(categoryName) {
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
            if(!category.hasErrors()) {
                crmContact.addToCategories(category)
                crmContact.save()
            }
        }
        return category
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
                address.type = createAddressType(name: "Postal Address", param: "postal", true)
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
                address.type = createAddressType(name: "Postal Address", param: "postal", true)
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

    private Map fixFirstLastName(Map params) {
        if (params.firstName && !params.lastName) {
            def tmp = params.firstName.split(' ')
            params.firstName = tmp[0]
            if (tmp.size() > 1) {
                params.lastName = tmp[1..-1].join(' ')
            }
        }
        return params
    }

    private Map extractAddress(Map params, String prefix = 'address') {
        def address = params.remove(prefix)
        def keys = params.keySet().toList() // toList() because clone not supported

        for (p in keys) {
            if (p.startsWith(prefix + '.')) {
                params.remove(p)
            }
        }
        return address
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
                address.type = "postal"
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
                    crmContact = CrmContact.findByIdAndTenantId(Long.valueOf(params.id), tenant)
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
                    crmContact = CrmContact.findByIdAndTenantId(Long.valueOf(params.id), tenant)
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
                crmContact = CrmContact.findByIdAndTenantId(Long.valueOf(params.id), tenant)
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

        // CrmContactRelation has no belongsTo (CrmContact) so we must manually delete all relations first.
        CrmContactRelation.executeUpdate("delete CrmContactRelation r where r.a = :contact or r.b = :contact", [contact: crmContact])

        crmContact.delete()

        def username = crmSecurityService.currentUser?.username
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
