package grails.plugins.crm.contact

import grails.plugins.crm.core.TenantUtils
import spock.lang.Ignore
import spock.lang.Shared

/**
 * Test import of contacts using the crm-import plugin.
 */
class CrmContactImportSpec extends grails.plugin.spock.IntegrationSpec {

    def crmContactService
    def crmImportService

    @Shared type

    def setup() {
        type = crmContactService.createAddressType(name: "Postal Address", param: "postal").save(failOnError: true, flush: true)
    }

    @Ignore
    def "import some companies"() {
        given:
        def file = File.createTempFile("crm", ".csv")
        file.deleteOnExit()
        file.withPrintWriter("UTF-8") { out ->
            out.println('CustomerNo,CompanyName,Address,Zip,Phone,Email')
            out.println('1234-0,"ACME Inc","212 Grails Street","789 0","+46 555 55555","info@acme.com"')
            out.println('17-32,"Spring Code","31 Enterprise Road","76 54","+1 555 1234","info@springcode.com"')
            out.println('2013,"Groovy Labs","184 Dynamic Avenue","","","info@groovylabs.com"')
            out.println('2013,"Groovy Labs","184 Dynamic Avenue","","+1 234 5678","info@groovylabs.com"')
        }

        when:
        crmImportService?.load(file) {
            data {
                username = 'demo'
            }
            crmContactImporter(description: "Import CrmContact (company) records", key: 'company') {
                data {
                    ssn = CustomerNo?.replaceAll('-', '')
                    name = CompanyName
                    address1 = Address
                    postalCode = Zip?.replaceAll(' ', '')
                    city = City
                    telephone = Phone
                    email = Email
                    tag = 'Import2012'
                }
                match(update: true) { data ->
                    eq('ssn', data.ssn)
                    eq('tenantId', TenantUtils.getTenant())
                }
            }
        }

        then:
        CrmContact.count() == 3
        CrmContact.findBySsn('2013').telephone == "+1 234 5678"
    }
}
