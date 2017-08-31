package grails.plugins.crm.contact

import spock.lang.Shared

class CrmContactServiceSpec extends grails.test.spock.IntegrationSpec {

    def crmContactService

    @Shared
            typePostal
    @Shared
            typeVisit
    @Shared
            employer

    def setup() {
        typePostal = crmContactService.createAddressType(name: "Postal Address", param: "postal").save(failOnError: true, flush: true)
        typeVisit = crmContactService.createAddressType(name: "Visiting Address", param: "visit").save(failOnError: true, flush: true)
        employer = crmContactService.createRelationType(name: "Employer", param: "employer", true)
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
        def person1 = crmContactService.createPerson(firstName: "Joe", lastName: "Average", true)
        def person2 = crmContactService.createPerson(firstName: "Jane", lastName: "Average", related: company,
                address: [type    : typePostal, address1: "Test Street 10",
                          address2: "P.O Box 1234", postalCode: "12345", city: "Test City"], true)
        def person3 = crmContactService.createPerson(firstName: "Jason", lastName: "Average", related: [company, employer], true)
        crmContactService.addRelation(person1, company, employer, true)

        then:
        person1.addresses == null || person1.addresses.isEmpty() // No own address
        person1.address.toString() == "Test Street 1, P.O Box 1234, 12345 Test City" // Company address
        person1.address.preferred

        person2.addresses.size() == 1
        person2.address.toString() != person1.address.toString()
        person2.address.address1 == "Test Street 10"
        person2.address.preferred

        person3.addresses == null || person3.addresses.isEmpty() // No own address
        person3.address.toString() == "Test Street 1, P.O Box 1234, 12345 Test City" // Company address
        person3.address.preferred

        when:
        company.refresh()

        then:
        company.children.size() == 0
        person1.parent == null
        person2.parent == null
        person3.parent == null
        company.relations.findAll { it.getRelated(company).firstName == "Joe" }.size() == 1
        company.relations.findAll { it.getRelated(company).firstName == "Jane" }.size() == 1
        company.relations.findAll { it.getRelated(company).firstName == "Jason" }.size() == 1
        company.relations.findAll { it.getRelated(company).lastName == "Average" }.size() == 3

        when:
        def result = crmContactService.list([related: 'Test Company', person: true], [:])

        then:
        result.size() == 3

        when:
        result = crmContactService.list([related: 'Test Company', person: true, name: 'Joe'], [:])

        then:
        result.size() == 1

        when:
        result = crmContactService.list([related: 'No Company', person: true], [:])

        then:
        result.size() == 0
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
                address: [type: typePostal, address1: "Test Street 1", postalCode: "THIS POSTAL CODE IS WAY TOO LOOONG", city: "Test City"],
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

    def "create and list category types"() {
        when:
        crmContactService.createCategoryType(name: "Test #1", param: "test1", true)
        crmContactService.createCategoryType(name: "Test #2", param: "test2", true)
        crmContactService.createCategoryType(name: "Test #3", param: "test3", true)

        then:
        crmContactService.listCategoryType("test").size() == 3
        crmContactService.listCategoryType("test1").size() == 1
        crmContactService.listCategoryType("test2").size() == 1
        crmContactService.listCategoryType("test3").size() == 1

        when:
        CrmContactCategoryType.withTransaction {
            crmContactService.listCategoryType("test").each { type ->
                crmContactService.deleteCategoryType(type)
            }
        }

        then:
        crmContactService.listCategoryType("test").size() == 0
    }

    def "find contacts by tag"() {
        when:
        new CrmContact(firstName: "Developer", lastName: "One").save(failOnError: true).setTagValue("grails").setTagValue("groovy").setTagValue("java").setTagValue("scala")
        new CrmContact(firstName: "Developer", lastName: "Two").save(failOnError: true).setTagValue("groovy").setTagValue("java").setTagValue("scala")
        new CrmContact(firstName: "Developer", lastName: "Three").save(failOnError: true).setTagValue("java").setTagValue("scala")
        new CrmContact(firstName: "Developer", lastName: "Four").save(failOnError: true).setTagValue("grails").setTagValue("groovy")
        new CrmContact(firstName: "Developer", lastName: "Five").save(failOnError: true).setTagValue("grails").setTagValue("groovy").setTagValue("java")

        then:
        crmContactService.list([tags: "java"], [:]).size() == 4
        crmContactService.list([tags: "java,scala"], [:]).size() == 4
        crmContactService.list([tags: "groovy&grails"], [:]).size() == 3
    }

    def "find contacts by parent"() {
        when:
        def corp = new CrmContact(name: "ACME Corporation").save(failOnError: true)
        def company1 = new CrmContact(name: "ACME Supplies Inc.", parent: corp).save(failOnError: true)
        def company2 = new CrmContact(name: "ACME Sales Inc.", parent: corp).save(failOnError: true)

        then:
        crmContactService.list([parent: "ACME Corporation"], [:]).size() == 2
        crmContactService.list([parent: corp.id], [:]).size() == 2
    }

    def "find contacts by related"() {
        given:
        def family = crmContactService.createRelationType(name: "Family", true)
        def father = new CrmContact(firstName: "Father", lastName: "Grails").save(failOnError: true)
        def mother = new CrmContact(firstName: "Mother", lastName: "Grails").save(failOnError: true)
        def daughter = new CrmContact(firstName: "Daughter", lastName: "Grails").save(failOnError: true)
        def son = new CrmContact(firstName: "Son", lastName: "Grails").save(failOnError: true)
        def dog = new CrmContact(firstName: "Dog", lastName: "Grails").save(failOnError: true)

        when:
        crmContactService.addRelation(son, father, family, true)
        crmContactService.addRelation(son, mother, family, false)
        crmContactService.addRelation(daughter, father, family, true)
        crmContactService.addRelation(daughter, mother, family, false)
        crmContactService.addRelation(mother, father, family, false)

        then:
        crmContactService.list().size() == 5
        crmContactService.list([related: "Father Grails"], [:]).size() == 3
        crmContactService.list([related: father.id], [:]).size() == 3
    }

    def "create contact information"() {
        when: "create contact info using strict property names"
        def info = crmContactService.createContactInformation(firstName: 'Joe', lastName: 'Average', companyName: 'IBM',
                address: [address1: "123 Streetname", postalCode: '55555', city: 'The City'])

        then:
        info.fullName == "Joe Average, IBM"
        info.fullAddress == "123 Streetname, 55555 The City"
        info.addressInformation.address1 == '123 Streetname'

        when: "create contact info using property name aliases 'company' and 'address'"
        info = crmContactService.createContactInformation(firstName: 'Joe', lastName: 'Average', company: 'IBM',
                address: "123 Streetname, 55555 The City")

        then:
        info.fullName == "Joe Average, IBM"
        info.fullAddress == "123 Streetname, 55555 The City"
        info.addressInformation.address1 == "123 Streetname, 55555 The City"
    }
}
