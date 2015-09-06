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

    def "relations on a transent instance should return null"() {
        when:
        def contact = new CrmContact()

        then:
        contact.getRelations() == null
        contact.getPrimaryRelation() == null
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
        employee1.getPrimaryRelation().name == "ACME Ltd."
        employee2.getRelations().size() == 1
        employee2.getPrimaryRelation().name == "ACME Ltd."
        employee3.getRelations().size() == 0
        employee3.getPrimaryRelation() == null

        when:
        crmContactService.deleteContact(company)

        then:
        CrmContactRelation.count() == count

        employee1.getRelations().size() == 0
        employee2.getRelations().size() == 0
    }
}
