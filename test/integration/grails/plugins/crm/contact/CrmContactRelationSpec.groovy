package grails.plugins.crm.contact

import spock.lang.Shared

/**
 * Test relations between contacts.
 */
class CrmContactRelationSpec extends grails.test.spock.IntegrationSpec {

    def crmContactService

    @Shared type

    def setup() {
        type = crmContactService.createRelationType(name: "Test", param: "test", true)
    }

    def "add relation between contacts"() {
        given:
        def count = CrmContactRelation.count()
        def company = crmContactService.createCompany(name: "ACME Ltd.", true)
        def employee1 = crmContactService.createPerson(firstName: "Employee", lastName: "One", true)
        def employee2 = crmContactService.createPerson(firstName: "Employee", lastName: "Two", true)
        def employee3 = crmContactService.createPerson(firstName: "Employee", lastName: "Three", true)

        when:
        crmContactService.addRelation(employee1, company, "test", true)
        crmContactService.addRelation(employee2, company, "test", true)

        then:
        company.getRelations().size() == 2
        employee1.getRelations().size() == 1
        employee2.getRelations().size() == 1
        employee3.getRelations().size() == 0

        when:
        crmContactService.deleteContact(company)

        then:
        CrmContactRelation.count() == count

        employee1.getRelations().size() == 0
        employee2.getRelations().size() == 0
    }
}
