#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License. See accompanying LICENSE file.
#

import sys
import os
import subprocess
import time
import argus_install
import re

cmd = sys.argv[0]
app_type = sys.argv[1]


service_entry = '--service' in sys.argv
configure_entry = '--configure' in sys.argv


if service_entry:
	try:
		argus_install.run_setup(cmd, app_type)
		jdk_options = argus_install.get_jdk_options()
		class_path = argus_install.get_argus_classpath()
		java_class = 'com.xasecure.server.tomcat.EmbededServer'
		class_arguments = ''
	
		from xml.dom.minidom import getDOMImplementation
		dom = getDOMImplementation()
		xmlDoc = dom.createDocument(None, 'service', None)
		xmlDocRoot = xmlDoc.documentElement
		arguments = ' '.join([''.join(jdk_options), '-cp', class_path, java_class, class_arguments])
	
		
		def appendTextElement(name, value):
			elem = xmlDoc.createElement(name)
			elem.appendChild(xmlDoc.createTextNode(value))
			xmlDocRoot.appendChild(elem)
		
		appendTextElement('id', app_type)
		appendTextElement('name', app_type)
		appendTextElement('description', 'This service runs ' + app_type)
		appendTextElement('executable', argus_install.get_java_env())
		appendTextElement('arguments', arguments)
		appendTextElement('logmode', "append")
		
		# print tree.toprettyxml(indent=' ')
		uglyXml = xmlDoc.toprettyxml(indent='  ')
		text_re = re.compile('>\n\s+([^<>\s].*?)\n\s+</', re.DOTALL)    
		prettyXml = text_re.sub('>\g<1></', uglyXml)
	
		print prettyXml
	except:	
		sys.exit()

	
if configure_entry:
	try:
		argus_install.configure()
	except:
		print "######################## Argus Configure failed! #######################"
		sys.exit(1)

	sys.exit(0)
	
			

