/*
 * Copyright (c) 2014 Goran Ehrsson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package grails.plugins.crm.contact

import org.codehaus.groovy.grails.web.metaclass.BindDynamicMethod

//import grails.plugins.crm.dataimport.CrmImport

/**
 * Importer implementation that import CrmContact instances.
 */
//@CrmImport(CrmContact)
class CrmContactImporter {

    def grailsApplication
    def crmContactService

    def beforeImport(task) {
    }

    def parse(task, data) {
        def crmContact
        if (task.matchCriteria != null) {
            crmContact = match(data, task.matchCriteria)
        }
        if (!crmContact) {
            //crmContact = crmContactService.createCompany(data)
            crmContact = new CrmContact()
        } else if (!task.matchParams?.update) {
            return
        }
        bindData(crmContact, data)
        crmContact.save(failOnError: true, flush:true)
        log.info "Saved contact: $crmContact"
    }

    def afterImport(task) {
    }

    def match(Map data, Closure crit) {
        CrmContact.createCriteria().get(crit.curry(data))
    }

    protected Object bindData(Object target, Map params) {
        def args = [target, params, [include: CrmContact.BIND_WHITELIST]]
        new BindDynamicMethod().invoke(target, 'bind', args.toArray())
    }

}
