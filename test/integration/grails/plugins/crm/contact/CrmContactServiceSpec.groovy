package grails.plugins.crm.contact

import spock.lang.Shared

class CrmContactServiceSpec extends grails.plugin.spock.IntegrationSpec {

    def crmContactService

    @Shared typePostal
    @Shared typeVisit

    def setup() {
        typePostal = crmContactService.createAddressType(name: "Postal Address", param: "postal").save(failOnError: true, flush: true)
        typeVisit = crmContactService.createAddressType(name: "Visiting Address", param: "visit").save(failOnError: true, flush: true)
    }

    def "create a new company with two employees"() {
        when:
        def company = crmContactService.createCompany(name: "Test Company", address: [type: typePostal, address1: "Test Street 1", address2: "P.O Box 1234", postalCode: "12345", city: "Test City"])

        then:
        !company.hasErrors()

        when:
        company.save(failOnError: true, flush: true)

        then:
        company.address.toString() == "Test Street 1, P.O Box 1234, 12345 Test City"

        when:
        def person1 = crmContactService.createPerson(firstName: "Joe", lastName: "Average", parent: company)
        def person2 = crmContactService.createPerson(firstName: "Jane", lastName: "Average", parent: company,
                address: [type: typePostal, address1: "Test Street 10", address2: "P.O Box 1234", postalCode: "12345", city: "Test City"])

        then:
        !person1.hasErrors()
        !person2.hasErrors()

        when:
        person1.save(failOnError: true, flush: true)
        person2.save(failOnError: true, flush: true)

        then:
        person1.addresses == null || person1.addresses.isEmpty() // No own address
        person1.address.toString() == "Test Street 1, P.O Box 1234, 12345 Test City" // Company address
        person1.address.preferred

        person2.addresses.size() == 1
        person2.address.toString() != person1.address.toString()
        person2.address.address1 == "Test Street 10"
        person2.address.preferred

        when:
        company.refresh()

        then:
        company.children.size() == 2
        company.children.findAll { it.firstName == "Joe" }.size() == 1
        company.children.findAll { it.firstName == "Jane" }.size() == 1
        company.children.findAll { it.lastName == "Average" }.size() == 2
    }

    def "create a company with two different addresses (postal and visit)"() {

        when: "create a company with two addresses"
        def company = crmContactService.createCompany(name: "Federal Company",
                addresses: [[type: typePostal, address1: "Test Street 1", postalCode: "11111", city: "City 1"],
                        [type: "visit", address1: "Test Street 2", postalCode: "22222", city: "City 2"]],
                true)

        then: "the company has two addresses"
        company.addresses.size() == 2
        company.getAddress('postal').postalCode == '11111'
        company.getAddress('visit').postalCode == '22222'
        company.getAddress('delivery') == null
    }

    def "try to insert invalid address"() {
        when:
        def company = crmContactService.createCompany(name: "Invalid Company",
                address: [type: typePostal, address1: "Test Street 1", postalCode: "A POSTAL CODE THAT IS TOO LONG", city: "Test City"],
                true)
        then:
        company.hasErrors()
        company.errors.allErrors.find { it }.code == 'maxSize.exceeded'
    }

    def "try to create company with duplicate number"() {
        given:
        def first = crmContactService.createCompany(name: "I was first!",
                address: [type: typePostal, address1: "Test Street 1", postalCode: "12345", city: "Test City"],
                true)
        def second = crmContactService.createCompany(name: "I was second!",
                address: [type: typePostal, address1: "Test Street 2", postalCode: "23456", city: "Test City"],
                true)
        def third = crmContactService.createCompany(name: "I was third!",
                address: [type: typePostal, address1: "Test Street 3", postalCode: "34567", city: "Test City"],
                true)

        when: "Try to create a company with same number as first company"
        def test = crmContactService.createCompany(name: "I was last!", number: first.number,
                address: [type: typePostal, address1: "Test Street 9", postalCode: "99999", city: "Test City"],
                true)
        then:
        Integer.valueOf(second.number) == (Integer.valueOf(first.number) + 1)
        Integer.valueOf(third.number) == (Integer.valueOf(second.number) + 1)
        Integer.valueOf(test.number) == (Integer.valueOf(third.number) + 1)
    }

    def "create CrmAddressType without param"() {
        when:
        def t0 = crmContactService.createAddressType(name: "This address type has a specified parameter", param: "foo", true)
        def t1 = crmContactService.createAddressType(name: "Address type is ok", true)
        def t2 = crmContactService.createAddressType(name: "Address type is too long for param", true)

        then:
        t0.param == 'foo'
        t1.param == 'address-type-is-ok'
        t2.param == 'address-type-is-too'
    }

    def "CrmAddressType equals and hashcode"() {
        when: "create two equal instances and compare them"
        def t1 = crmContactService.createAddressType(name: "This address type has a specified parameter", param: "foo", true)
        def t2 = new CrmAddressType(orderIndex: t1.orderIndex, name: t1.name, param: t1.param)
        t2.id = t1.id

        then:
        t1.id == t2.id
        t1.orderIndex == t2.orderIndex
        t1.name == t2.name
        t1.param == t2.param
        t1 == t2
        !t1.is(t2)
    }
}
