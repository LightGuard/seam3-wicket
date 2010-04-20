package org.jboss.weld.wicket;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

import javax.enterprise.context.Conversation;
import javax.enterprise.inject.AmbiguousResolutionException;
import javax.enterprise.inject.UnsatisfiedResolutionException;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.apache.wicket.IRequestTarget;
import org.apache.wicket.Page;
import org.apache.wicket.Response;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.protocol.http.WebRequest;
import org.apache.wicket.protocol.http.WebRequestCycle;
import org.apache.wicket.request.target.component.BookmarkablePageRequestTarget;
import org.apache.wicket.request.target.component.IPageRequestTarget;
import org.jboss.weld.Container;
import org.jboss.weld.context.ContextLifecycle;
import org.jboss.weld.context.ConversationContext;
import org.jboss.weld.conversation.ConversationImpl;
import org.jboss.weld.conversation.ConversationManager;
import org.jboss.weld.servlet.ConversationBeanStore;

/**
 * WeldRequestCycle is a subclass of the standard wicket WebRequestCycle which:
 * <ul>
 * <li>restores long-running conversations specified in wicket page metadata
 * when a page target is first used.
 * <li>propagates long running conversations to new page targets by specifying
 * the above metadata
 * <li>propagates long running conversations across redirects through the use of
 * a request parameter if the redirect is handled with a BookmarkablePageRequest
 * <li>Sets up the conversational context when the request target is set
 * <li>Tears down the conversation context on detach() of the RequestCycle
 * </ul>
 * 
 * @see WeldWebRequestCycleProcessor Which handles propogation of conversation
 *      data for newly-started long running conversations, by storing their ids
 *      in the page metadata
 * @author cpopetz
 * 
 */
public class WeldRequestCycle extends WebRequestCycle
{

   public WeldRequestCycle(WebApplication application, WebRequest request, Response response)
   {
      super(application, request, response);
   }

   /**
    * Override to set up the conversation context and to choose the conversation
    * if a conversation id is present in target metadata.
    */
   @Override
   protected void onRequestTargetSet(IRequestTarget target)
   {
      super.onRequestTargetSet(target);

      Page page = null;
      if (target instanceof IPageRequestTarget)
      {
         page = ((IPageRequestTarget) target).getPage();
      }

      // Two possible specifications of cid: page metadata or request url; the
      // latter is used to propagate the conversation to mounted (bookmarkable)
      // paths after a redirect

      String specifiedCid = null;
      if (page != null)
      {
         specifiedCid = page.getMetaData(WeldMetaData.CID);
      }
      else
      {
         specifiedCid = request.getParameter("cid");
      }

      BeanManager manager = BeanManagerLookup.getBeanManager();

      ConversationImpl conversation = (ConversationImpl) getInstanceByType(manager, Conversation.class);

      // restore a conversation if it exists and we aren't already in it
      if (specifiedCid != null && (conversation == null || !specifiedCid.equals(conversation.getUnderlyingId())))
      {
         getInstanceByType(manager, ConversationManager.class).beginOrRestoreConversation(specifiedCid);
      }


      ConversationContext conversationContext = Container.instance().services().get(ContextLifecycle.class).getConversationContext();
      // Now set up the conversational context if it isn't already
      if (!conversationContext.isActive())
      {
         // TODO account for invalidated session
         conversationContext.setBeanStore(new ConversationBeanStore(((WebRequest) request).getHttpServletRequest().getSession(), false, conversation.getUnderlyingId()));
         conversationContext.setActive(true);
      }

      // handle propagation of existing long running converstaions to new
      // targets
      if (!conversation.isTransient())
      {
         // Note that we can't propagate conversations with other redirect
         // targets like RequestRedirectTarget through this mechanism, because
         // it does not provide an interface to modify its target URL. If
         // propagation with those targets is to be supported, it needs a custom
         // Response subclass.
         if (isRedirect() && target instanceof BookmarkablePageRequestTarget)
         {
            BookmarkablePageRequestTarget bookmark = (BookmarkablePageRequestTarget) target;
            // if a cid has already been specified, don't override it
            if (!bookmark.getPageParameters().containsKey("cid"))
               bookmark.getPageParameters().add("cid", conversation.getUnderlyingId());
         }

         // If we have a target page, propagate the conversation to the page's
         // metadata
         if (page != null)
         {
            page.setMetaData(WeldMetaData.CID, conversation.getUnderlyingId());
         }
      }


   }

   @SuppressWarnings("unchecked")
   private <T> T getInstanceByType(BeanManager manager, Class<T> beanType, Annotation... bindings)
   {
      Bean<T> bean = (Bean<T>) ensureUniqueBean(beanType, manager.getBeans(beanType, bindings));
      return (T) manager.getReference(bean, beanType, manager.createCreationalContext(bean));
   }

   private static Bean<?> ensureUniqueBean(Type type, Set<Bean<?>> beans)
   {
      if (beans.size() == 0)
      {
         throw new UnsatisfiedResolutionException("Unable to resolve any Web Beans of " + type);
      }
      else if (beans.size() > 1)
      {
         throw new AmbiguousResolutionException("More than one bean available for type " + type);
      }
      return beans.iterator().next();
   }

   @Override
   public void detach()
   {
      super.detach();
      ConversationContext conversationContext = Container.instance().services().get(ContextLifecycle.class).getConversationContext();
      // cleanup and deactivate the conversation context
      if (conversationContext.isActive())
      {
         ConversationManager conversationManager = getInstanceByType(BeanManagerLookup.getBeanManager(), ConversationManager.class);
         conversationManager.cleanupConversation();
         conversationContext.setActive(false);
      }
   }

}