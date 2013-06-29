package grails.plugins.crm.contact

/**
 * Test CrmContact domain class.
 */
class CrmContactSpec extends grails.plugin.spock.IntegrationSpec {

    def grailsApplication

    def "test constraints"() {
        when:
        new CrmContact(tenantId: 3, number: "42", name: "Grails Inc.").save(failOnError: true, flush: true)
        def contact = new CrmContact()
        then:
        !contact.validate()
        "blank" == contact.errors.getFieldError("firstName").code
        "blank" == contact.errors.getFieldError("lastName").code
        "blank" == contact.errors.getFieldError("name").code

        when:
        contact = new CrmContact(tenantId: 3, number: "42", name: "I'm a duplicate")
        then:
        !contact.validate()
        "unique" == contact.errors.getFieldError("number").code

        when:
        contact = new CrmContact(tenantId: 1, number: "1", firstName: "Lisa")
        then:
        contact.validate()

        when:
        contact = new CrmContact(tenantId: 1, number: "2", lastName: "Orwell")
        then:
        contact.validate()

        when:
        contact = new CrmContact(tenantId: 1, number: "3", name: "Acme Inc.")
        then:
        contact.validate()
    }

    def "name concatenation"() {
        when:
        def contact = new CrmContact(firstName: 'Joe')
        then:
        contact.lastName == null
        contact.name == null
        contact.validate()
        contact.name == 'Joe'

        when:
        contact = new CrmContact(lastName: 'Average')
        then:
        contact.firstName == null
        contact.name == null
        contact.validate()
        contact.name == 'Average'

        when:
        contact = new CrmContact(firstName: 'Joe', lastName: 'Average')
        then:
        contact.name == null
        contact.validate()
        contact.name == 'Joe Average'
        contact.toString() == 'Joe Average'
    }

    def "phonetic name"() {
        when:
        def contact = new CrmContact(firstName: 'Bjorn', lastName: 'Erikson')
        then:
        contact.namePhonetic == null
        contact.validate()
        contact.namePhonetic == 'PJRN'
    }

    def "D-U-N-S Number"() {
        given: "enable strict DUNS validation"
        grailsApplication.config.crm.contact.duns.strict = true

        when: "create a company and assign D-U-N-S number (including dashes)"
        def contact = new CrmContact(name: 'D&B', duns: "12-345-6789")

        then: "dashes should be removed"
        contact.validate()
        contact.duns == '123456789'

        when: "relaxed/no validation"
        grailsApplication.config.crm.contact.duns.strict = false
        contact = new CrmContact(name: 'D&B', duns: "12-555-4711")

        then: "dashes should NOT be removed"
        contact.validate()
        contact.duns == '12-555-4711'

    }

    def "test fullName"() {
        when:
        def company = new CrmContact(name: 'Groovy Corporation').save(failOnError: true)
        def person = new CrmContact(name: 'George Groovy', parent: company).save(failOnError: true)

        then:
        company.fullName == 'Groovy Corporation'
        person.fullName == 'George Groovy, Groovy Corporation'
    }

}
