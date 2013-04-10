package grails.plugins.crm.contact

import spock.lang.Shared

/**
 * Test relations between contacts.
 */
class CrmContactRelationSpec extends grails.plugin.spock.IntegrationSpec {

    def crmContactService

    @Shared type

    def setup() {
        type = crmContactService.createRelationType(name: "Test", param: "test", true)
    }

    def "add relation between contacts"() {
        given:
        def company = crmContactService.createCompany(name: "ACME Ltd.", true)
        def employee1 = crmContactService.createPerson(firstName: "Employee", lastName: "One", true)
        def employee2 = crmContactService.createPerson(firstName: "Employee", lastName: "Two", true)
        def employee3 = crmContactService.createPerson(firstName: "Employee", lastName: "Three", true)

        when:
        crmContactService.addRelation(company, employee1, "test")
        crmContactService.addRelation(company, employee2, "test")

        then:
        company.getRelations().size() == 2
        employee1.getRelations().size() == 1
        employee2.getRelations().size() == 1
        employee3.getRelations().size() == 0
    }
}
