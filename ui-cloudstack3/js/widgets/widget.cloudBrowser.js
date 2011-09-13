(function($, cloudStack) {
  cloudStack.ui.api.browser = {};

  /**
   * Breadcrumb-related functions
   */
  var _breadcrumb = cloudStack.ui.api.browser.breadcrumb = {
    /**
     * Generate new breadcrumb
     */
    create: function($panel, title) {
      // Attach panel as ref for breadcrumb
      return cloudStack.ui.event.elem(
        'cloudBrowser', 'breadcrumb',
        $('<div>')
          .append($('<li>').html(title))
          .append($('<div>').addClass('end'))
          .children(),
        {
          panel: $panel
        }
      );
    },

    /**
     * Get breadcrumbs matching specified panels
     */
    filter: function($panels) {
      var $breadcrumbs = $('#breadcrumbs ul li');
      var $result = $([]);

      $panels.each(function() {
        var $panel = $(this);

        $.merge(
          $result,
          $.merge(
            $breadcrumbs.filter(function() {
              return $(this).index('#breadcrumbs ul li') == $panel.index();
            }),

            // Also include ends
            $breadcrumbs.siblings('div.end').filter(function() {
              return $(this).index('div.end') == $panel.index() + 1;
            })
          )
        );
      });

      return $result;
    }
  };

  /**
   * Container-related functions
   */
  var _container = cloudStack.ui.api.browser.container = {
    /**
     * Get all panels from container
     */
    panels: function($container) {
      return $container.find('div.panel');
    }
  };

  /**
   * Panel-related functions
   */
  var _panel = cloudStack.ui.api.browser.panel = {
    /**
     * Compute width of panel, relative to container
     */
    width: function($container, options) {
      options = options ? options : {};
      var width = $container.find('div.panel').size() < 1 || options.maximized == true ? 
        $container.width() : $container.width() - $container.width() / 4;

      return width;
    },

    /**
     * Get left position
     */
    position: function($container, options) {
      return $container.find('div.panel').size() <= 1 || options.maximized == true ?
        0 : _panel.width($container, options) - _panel.width($container, options) / 1.5;
    },

    /**
     * Get the top panel z-index, for proper stacking
     */
    topIndex: function($container) {
      var base = 1000; // Minimum z-index

      return Math.max.apply(
        null,
        $.map(
          $container.find('div.panel'),
          function(elem) {
            return parseInt($(elem).css('z-index')) || base;
          }
        )
      ) + 1;
    },

    /**
     * State when panel is outside container
     */
    initialState: function($container) {
      return {
        left: $container.width()
      };
    },

    /**
     * Get panel and breadcrumb behind specific panel
     */
    lower: function($container, $panel) {
      return _container.panels($container).filter(function() {
        return $(this).index() < $panel.index();
      });
    },

    /**
     * Get panel and breadcrumb stacked above specific panel
     */
    higher: function($container, $panel) {
      return _container.panels($container).filter(function() {
        return $(this).index() > $panel.index();
      });
    },

    /**
     * Generate new panel
     */
    create: function($container, options) {
      var $panel = $('<div>').addClass('panel').css(
        {
          position: 'absolute',
          width: _panel.width($container, { maximized: options.maximized }),
          zIndex: _panel.topIndex($container)
        }
      ).append(
        // Shadow
        $('<div>').addClass('shadow')
      ).append(options.data);

      if (options.maximized) $panel.addClass('always-maximized');

      return $panel;
    }
  };

  /**
   * Browser -- jQuery widget
   */
  $.widget('cloudStack.cloudBrowser', {
    _init: function() {
      this.element.addClass('cloudStack-widget cloudBrowser');
      $('#breadcrumbs').append(
        $('<ul>')
      );
    },

    /**
     * Make target panel the top-most
     */
    selectPanel: function(args) {
      var $panel = args.panel;
      var $container = this.element;
      var $toShow = _panel.lower($container, $panel);
      var $toRemove = _panel.higher($container, $panel);

      _breadcrumb.filter($toRemove).remove();
      $toRemove.animate(
        _panel.initialState($container),
        {
          duration: 500,
          complete: function() {
            $(this).remove();
          }
       }
      );
      $toShow.show();
      $panel.show().removeClass('reduced');
    },

    /**
     * Toggle selected panel as fully expanded, hiding/showing other panels
     */
    toggleMaximizePanel: function(args) {
      var $panel = args.panel;
      var $container = this.element;
      var $toHide = $panel.siblings(':not(.always-maximized)');

      if (args.panel.hasClass('maximized')) {
        $panel.removeClass('maximized');
        $panel.addClass('reduced');
        $toHide.animate({ left: _panel.position($container, {}) },
                        { duration: 500 });
      } else {
        $panel.removeClass('reduced');
        $panel.addClass('maximized');
        $toHide.animate(_panel.initialState($container),
                        { duration: 500 });
      }
    },

    /**
     * Append new panel to end of container
     */
    addPanel: function(args) {
      var duration = 500;
      var $container = this.element;
      var $panel, $reduced, targetPosition;

      // Create panel
      $panel = _panel.create(this.element, {
        maximized: args.maximizeIfSelected,
        data: args.data
      });

      // Remove existing panels, if parent specified
      if (args.parent) {
        _breadcrumb.filter(args.parent.next()).remove();
        $container.find(args.parent.next()).remove();
      }

      // Append panel
      $panel.appendTo($container);
      _breadcrumb.create($panel, args.title).appendTo('#breadcrumbs ul');

      // Reduced appearance for previous panels
      $panel.siblings().filter(function() {
        return $(this).index() < $panel.index();
      }).addClass('reduced');

      // Panel initial state
      if ($panel.index() == 0) $panel.addClass('always-maximized');
      $panel.css(
        _panel.initialState($container, $panel)
      );

      // Panel slide-in
      targetPosition = _panel.position($container, {
        maximized: args.maximizeIfSelected
      });
      if (!$panel.index() || (args.parent && args.parent.index() < $panel.index() - 1)) {
        // Just show immediately if this is the first panel
        $panel.css(
          { left: targetPosition }
        );
        if (args.complete) args.complete($panel);
      } else {
        // Animate slide-in
        $panel.animate({ left: targetPosition }, {
          duration: duration,
          easing: 'easeOutCirc',
          complete: function() {
            // Hide panels
            $panel.siblings().filter(function() {
              return $(this).width() == $panel.width();
            });

            if (args.complete) args.complete($panel);
          }
        });
      };

      return $panel;
    },

    /**
     * Clear all panels
     */
    removeAllPanels: function(args) {
      this.element.find('div.panel').remove();
      $('#breadcrumbs').find('ul li').remove();
      $('#breadcrumbs').find('ul div.end').remove();
    }
  });

  $(window).bind('click', cloudStack.ui.event.bind(
    'cloudBrowser',
    {
      'breadcrumb': function($target, $browser, data) {
        $browser.cloudBrowser('selectPanel', { panel: data.panel });
      }
    }
  ));
})(jQuery, cloudStack);
