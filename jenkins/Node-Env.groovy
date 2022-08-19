def nodes = jenkins.model.Jenkins.get().computers.collect{ it.node.labelString }

nodes.removeAll { it.toLowerCase().startsWith('master') }
nodes.sort()
return nodes