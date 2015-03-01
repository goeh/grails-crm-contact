package grails.plugins.crm.contact

import grails.plugins.crm.core.TenantUtils

public class SelectionServiceSpec extends grails.test.spock.IntegrationSpec {

    def selectionService
    def gormSelection

    def testGet() {

        when:
        def contact = new CrmContact(number: "1", name: "Foo").save(flush: true)
        then:
        contact != null

        when:
        def result = selectionService.select("gorm://crmContact/get?id=${contact.id}")
        then:
        result != null
        result.name == "Foo"
    }

    def testList() {

        when:
        new CrmContact(number: "1", name: "Foo").save()
        new CrmContact(number: "2", name: "Bar").save()
        new CrmContact(number: "3", name: "Baz").save()
        def result = selectionService.select("gorm://crmContact/list?name=Ba", [max: 10])
        then:
        result != null
        result.size() == 2
        result.totalCount == 2
    }

    def testIntegerQuery() {
        when:
        new CrmContact(number: "1", birthYear: 1910, name: "One").save(flush: true)
        new CrmContact(number: "2", birthYear: 1920, name: "Two").save(flush: true)
        new CrmContact(number: "3", birthYear: 1930, name: "Three").save(flush: true)
        new CrmContact(number: "4", birthYear: 1940, name: "Four").save(flush: true)
        new CrmContact(number: "5", birthYear: 1950, name: "Five").save(flush: true)
        new CrmContact(number: "6", birthYear: 1960, name: "Six").save(flush: true)
        new CrmContact(number: "7", birthYear: 1970, name: "Seven").save(flush: true)
        new CrmContact(number: "8", birthYear: 1980, name: "Eight").save(flush: true)
        new CrmContact(number: "9", birthYear: 1990, name: "Nine").save(flush: true)
        new CrmContact(number: "10", birthYear: 2000, name: "Ten").save(flush: true)
        def result = selectionService.select("gorm://crmContact/list?birthYear=1970")
        then:
        result.size() == 1
        result.first().birthYear == 1970

        when:
        result = selectionService.select("gorm://crmContact/list?birthYear=" + "<1970".encodeAsURL())
        then:
        result.size() == 6
        !result.find {it.birthYear >= 1970}

        when:
        result = selectionService.select("gorm://crmContact/list?birthYear=" + ">1970".encodeAsURL())
        then:
        result.size() == 3
        !result.find {it.birthYear <= 1970}

        when:
        result = selectionService.select("gorm://crmContact/list?birthYear=1970-1980")
        then:
        result.size() == 2
        !result.find {it.birthYear < 1970 || it.birthYear > 1980 }
    }

    def testTenantQuery() {
        def result

        when:
        TenantUtils.withTenant(1L) {
            new CrmContact(number: "1", name: "One").save(flush: true)
            new CrmContact(number: "2", name: "Two").save(flush: true)
            new CrmContact(number: "3", name: "Three").save(flush: true)
            new CrmContact(number: "4", name: "Four").save(flush: true)
            new CrmContact(number: "5", name: "Five").save(flush: true)
        }
        TenantUtils.withTenant(2L) {
            new CrmContact(number: "1", name: "Six").save(flush: true)
            new CrmContact(number: "2", name: "Seven").save(flush: true)
            new CrmContact(number: "3", name: "Eight").save(flush: true)
            new CrmContact(number: "4", name: "Nine").save(flush: true)
            new CrmContact(number: "5", name: "Ten").save(flush: true)
            result = selectionService.select("gorm://crmContact/random", [max: 3])
        }

        then:
        result != null
        result.size() == 3
        !result.find {it.tenantId != 2L}
    }

    def testFixedCriteria() {
        when:
        new CrmContact(number: "1", name: "One").save(flush: true)
        new CrmContact(number: "2", name: "Two").save(flush: true)
        new CrmContact(number: "3", name: "Three").save(flush: true)
        new CrmContact(number: "4", name: "Four").save(flush: true)
        new CrmContact(number: "5", name: "Five").save(flush: true)
        new CrmContact(number: "6", name: "Six").save(flush: true)
        new CrmContact(number: "7", name: "Seven").save(flush: true)
        new CrmContact(number: "8", name: "Eight").save(flush: true)
        new CrmContact(number: "9", name: "Nine").save(flush: true)
        new CrmContact(number: "0", name: "Ten").save(flush: true)

        gormSelection.setCriteria(CrmContact) { query, params ->
            ilike('name', "T%")
        }
        def result = selectionService.select("gorm://crmContact/list")
        then:
        result != null
        result.size() == 3
        !result.find {it.name[0] != "T"}
    }

}
