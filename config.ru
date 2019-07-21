require './web'
require 'sidekiq/web'

if ENV['ENABLE_SIDEKIQ_WEB']
  run Rack::URLMap.new(
    '/' => Web,
    '/sidekiq' => Sidekiq::Web
  )
else
  run Rack::URLMap.new(
    '/' => Web
  )
end
