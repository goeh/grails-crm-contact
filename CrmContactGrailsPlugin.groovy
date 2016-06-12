/*
 * Copyright (c) 2015 Goran Ehrsson.
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

import grails.plugins.crm.contact.CrmContact

class CrmContactGrailsPlugin {
    def groupId = ""
    def version = "2.4.2-SNAPSHOT"
    def grailsVersion = "2.2 > *"
    def dependsOn = [:]
    def loadAfter = ['crmCore', 'selection', 'crmTags']
    def pluginExcludes = [
            "grails-app/views/error.gsp",
            "src/groovy/grails/plugins/crm/contact/TestSecurityDelegate.groovy"
    ]
    def title = "GR8 CRM Contact Management Services"
    def author = "Goran Ehrsson"
    def authorEmail = "goran@technipelago.se"
    def description = '''
Provides "headless" Contact Management features for GR8 CRM applications.
This plugin provides no user interface, just domain classes and services for Contact Management.
For user interface see the crm-contact-ui plugin which provides a Twitter Bootstrap user interface.
'''
    def documentation = "http://gr8crm.github.io/plugins/crm-contact/"
    def license = "APACHE"
    def organization = [name: "Technipelago AB", url: "http://www.technipelago.se/"]
    def issueManagement = [system: "github", url: "https://github.com/goeh/grails-crm-contact/issues"]
    def scm = [url: "https://github.com/goeh/grails-crm-contact"]

    def doWithApplicationContext = { applicationContext ->

        // TODO Move helper methods to a utility class, or better to GormSelection.groovy?
        // Wildcard() is already in the selection plugin, but that would cause an unwanted compile-time dependency.
        // GormSelection could probably handle all simple properties (String, Number, Date) automatically.
        def wildcard = { prop, q ->
            q = q.toLowerCase()
            if (q.contains('*')) {
                ilike(prop, q.replace('*', '%'))
            } else if (q[0] == '=') {
                eq(prop, q[1..-1]) // Exact match.
            } else {
                ilike(prop, q + '%') // Starts with is default.
            }
        }

        def number = { prop, q ->
            if (q[0] == '<') {
                lt(prop, Integer.valueOf(q[1..-1]))
            } else if (q[0] == '>') {
                gt(prop, Integer.valueOf(q[1..-1]))
            } else if (q.indexOf('-') != -1) {
                def (from, to) = q.split('-').toList()
                between(prop, Integer.valueOf(from), Integer.valueOf(to))
            } else {
                eq(prop, Integer.valueOf(q))
            }
        }

        def gormSelection = applicationContext.getBean("gormSelection")
        gormSelection.setCriteria(CrmContact) { query, params ->
            // Setup criteria helpers
            wildcard.delegate = delegate
            wildcard.resolveStrategy = Closure.DELEGATE_FIRST
            number.delegate = delegate
            number.resolveStrategy = Closure.DELEGATE_FIRST

            // Take the simple (String) properties first.
            for (prop in ['firstName', 'lastName', 'name', 'ssn', 'title', 'email', 'url']) {
                if (query[prop]) {
                    wildcard(prop, query[prop])
                }
            }
            if (query.telephone) {
                or {
                    wildcard('telephone', query.telephone)
                    wildcard('mobile', query.telephone)
                }
            }
            // Integer properties are also easy.
            for (prop in ['birthYear', 'birthMonth', 'birthDay']) {
                if (query[prop]) {
                    number(prop, query[prop])
                }
            }
        }

        if(application.config.crm.contact.indexing.enabled) {
            applicationContext.eventTriggeringInterceptor.datastores.each { key, datastore ->
                def listener = new grails.plugins.crm.contact.AuditEventListener(datastore)
                applicationContext.addApplicationListener(listener)
            }
        }
    }

}
