/*
 * Copyright 2012 Goran Ehrsson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import grails.plugins.crm.contact.CrmContact

class CrmContactGrailsPlugin {
    // Dependency group
    def groupId = "grails.crm"
    // the plugin version
    def version = "1.1.2-SNAPSHOT"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "2.0 > *"
    // the other plugins this plugin depends on
    def dependsOn = [:]
    // Load after selection plugin since we add criteria to GormSelection
    def loadAfter = ['crmCore', 'selection']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp",
            "src/groovy/grails/plugins/crm/contact/TestSecurityDelegate.groovy"
    ]

    def title = "Grails CRM Contact"
    def author = "Goran Ehrsson"
    def authorEmail = "goran@technipelago.se"
    def description = '''
Provides "headless" contact management features for Grails CRM.
This plugin provides no user interface, just domain classes and services for contact management.
For user interface see the crm-contact-lite plugin which provides a twitter bootstrap user interface.
'''

    def documentation = "https://github.com/goeh/grails-crm-contact"
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
    }

}
