package grails.plugins.crm.contact

import spock.lang.Shared

/**
 * Test categories collection on CrmContact.
 */
class CrmContactCategorySpec extends grails.plugin.spock.IntegrationSpec {

    def crmContactService

    @Shared type1
    @Shared type2

    def setup() {
        type1 = crmContactService.createCategoryType(name: "Test 1", param: "test1", true)
        type2 = crmContactService.createCategoryType(name: "Test 2", param: "test2", true)
    }

    def "add category to contact"() {
        when:
        def count = CrmContactCategory.count()
        def company = crmContactService.createCompany(name: "ACME Ltd.", true)

        then:
        !company.categories

        when:
        crmContactService.addCategory(company, "test1")
        crmContactService.addCategory(company, "test2")

        then:
        company.categories.size() == 2


        when:
        crmContactService.addCategory(company, "test1")

        then:
        company.categories.size() == 2

        when:
        crmContactService.deleteContact(company)

        then:
        CrmContactCategory.count() == count
    }

    def "search contact by category"() {
        given:
        def c1 = crmContactService.createCompany(name: "ACME Ltd.", true)
        def c2 = crmContactService.createCompany(name: "Groovy Corporation", true)
        def c3 = crmContactService.createCompany(name: "Grails Industries", true)

        when:
        crmContactService.addCategory(c2, "test1")
        crmContactService.addCategory(c3, "test1")
        crmContactService.addCategory(c3, "test2")

        then:
        !c1.categories
        c2.categories.size() == 1
        c3.categories.size() == 2

        when:
        def result = crmContactService.list([category: "test1"], [:])

        then:
        result.size() == 2
        result.find { it.name == 'Groovy Corporation' }
        result.find { it.name == 'Grails Industries' }
        !result.find { it.name == 'ACME Ltd.' }

        when:
        result = crmContactService.list([category: "test2"], [:])

        then:
        result.size() == 1
        !result.find { it.name == 'Groovy Corporation' }
        result.find { it.name == 'Grails Industries' }
        !result.find { it.name == 'ACME Ltd.' }
    }
}
