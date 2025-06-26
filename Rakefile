require 'logstash/devutils/rake'

task :vendor do
  sh('./gradlew clean vendor')
end

task :prepare_geoip_resources do
  sh('./gradlew geoipTestResources')
end

task :java_test do
  sh('./gradlew test')
end